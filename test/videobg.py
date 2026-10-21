"""R7 — the user's own video really plays as the live background.

`video.play()` on a detached or unloaded element resolves happily in some
browsers, so this checks the element the user actually looks at: it must be
visible, must have decoded frames (`readyState`/`videoWidth`), and must still be
advancing a moment later. The same run also proves a custom URL from settings is
what gets loaded, which is how the user's own clip is applied.
"""
import asyncio
import json

from playwright.async_api import async_playwright

BASE = "http://localhost:12000"


async def main():
    fails = []
    async with async_playwright() as p:
        b = await p.chromium.launch(args=["--no-sandbox", "--autoplay-policy=no-user-gesture-required"])
        ctx = await b.new_context(viewport={"width": 412, "height": 915})
        pg = await ctx.new_page()
        pg.on("pageerror", lambda e: fails.append(f"js error: {e}"))
        await pg.goto(BASE, wait_until="networkidle")
        await pg.wait_for_timeout(1500)

        # ── bundled scene must really decode and advance ──────────
        await pg.evaluate("()=>window.ZY.BG.set('galaxy')")
        await pg.wait_for_timeout(1800)
        st = await pg.evaluate("""()=>{
          const v = document.getElementById('bg-video');
          const r = v.getBoundingClientRect();
          return { src: v.currentSrc || v.src, paused: v.paused, ready: v.readyState,
                   w: v.videoWidth, h: v.videoHeight, t: v.currentTime,
                   on: v.classList.contains('on'), visible: r.width > 0 && r.height > 0,
                   z: getComputedStyle(v).zIndex };
        }""")
        print("bundled scene:", st)
        if not st["src"] or "galaxy-eye" not in st["src"]:
            fails.append("bundled video scene did not load its source")
        if st["paused"]:
            fails.append("background video is paused")
        if st["ready"] < 2 or not st["w"]:
            fails.append(f"background video has no decoded frames (readyState={st['ready']}, w={st['w']})")
        if not st["on"] or not st["visible"]:
            fails.append("background video is not actually on screen")
        first = st["t"]
        await pg.wait_for_timeout(900)
        later = await pg.evaluate("()=>document.getElementById('bg-video').currentTime")
        print(f"playhead {first:.2f} → {later:.2f}")
        if later <= first:
            fails.append("background video is frozen (playhead not advancing)")

        # ── the user's own video URL (settings → custom) ──────────
        custom = "/assets/bg/dream.mp4"
        await pg.evaluate("()=>window.ZY.store.set('bgUrl', '/assets/bg/dream.mp4')")
        await pg.evaluate("()=>window.ZY.BG.set('custom', '/assets/bg/dream.mp4')")
        await pg.wait_for_timeout(1800)
        cst = await pg.evaluate("""()=>{
          const v = document.getElementById('bg-video');
          return { src: v.currentSrc || v.src, paused: v.paused, ready: v.readyState, w: v.videoWidth, t: v.currentTime };
        }""")
        print("custom scene:", cst)
        if "dream" not in (cst["src"] or ""):
            fails.append("custom background URL was not applied")
        if cst["paused"] or not cst["w"]:
            fails.append("custom background video is not playing")
        await pg.wait_for_timeout(800)
        later2 = await pg.evaluate("()=>document.getElementById('bg-video').currentTime")
        if later2 <= cst["t"]:
            fails.append("custom background video is frozen")

        # ── every scene must have a real, loadable asset ──────────
        scenes = await pg.evaluate("()=>window.ZY.BG.scenes.filter(s=>s.kind==='video').map(s=>({id:s.id,src:s.src}))")
        for s in scenes:
            code = await pg.evaluate("""async (u)=>{
              try { const r = await fetch(u, { method: 'GET', headers: { Range: 'bytes=0-1' } }); return r.status; }
              catch (e) { return 'ERR'; }
            }""", s["src"])
            if code not in (200, 206):
                fails.append(f"scene {s['id']} asset missing ({s['src']} → {code})")
        print(f"video scenes checked: {len(scenes)}")

        await b.close()
    print("FAILS:", fails if fails else "none")
    return 1 if fails else 0


raise SystemExit(asyncio.run(main()))
