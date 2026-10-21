"""The home screen really reacts when you speak.

The home screen is deliberately just the orb plus a live caption — the chat
transcript lives in the console sheet. So "what I said" must appear on the home
screen in `#live-text`, her reply must appear in the speech box `#speak-box`
(`#speak-text`) while it is being spoken, and the whole thing must be spoken in
Bangla. A real tool call must still execute.

Drives the real page and feeds a Bangla result through the same code path the
recogniser uses (STT.onFinal).
"""
import asyncio

from playwright.async_api import async_playwright

BASE = "http://localhost:12000"
USER_LINE = "তুমি কেমন আছো?"


async def main():
    fails = []
    async with async_playwright() as p:
        b = await p.chromium.launch(args=["--no-sandbox"])
        ctx = await b.new_context(viewport={"width": 412, "height": 915})
        pg = await ctx.new_page()
        pg.on("pageerror", lambda e: fails.append(f"js error: {e}"))
        await pg.add_init_script("localStorage.setItem('zyraxon:settings', JSON.stringify({tts:true,automic:false}));")
        await pg.goto(BASE, wait_until="networkidle")
        await pg.wait_for_timeout(2500)

        # Every spoken line goes through TTS.push, so instrument that one point.
        await pg.evaluate("""()=>{
          window.__spoken = [];
          const T = window.ZY.VOICE.TTS;
          const orig = T.push.bind(T);
          T.push = (t) => { window.__spoken.push(String(t)); return orig(t); };
        }""")

        # ── 1. the user's own words must land on the home screen ──
        await pg.evaluate("(t)=>window.ZY.VOICE.STT.onFinal(t)", USER_LINE)
        await pg.wait_for_timeout(800)
        live = await pg.evaluate("()=>document.getElementById('live-text').textContent")
        print("home caption:", repr(live))
        if USER_LINE not in live:
            fails.append("the user's spoken words are missing from the home caption")

        # ── 2. the reply must reach the speech box while it is spoken (TTS on) ──
        seen = []
        for _ in range(50):
            await pg.wait_for_timeout(500)
            st = await pg.evaluate("""()=>{
              const box = document.getElementById('speak-box');
              const txt = document.getElementById('speak-text');
              return { hidden: box.hidden, text: txt.textContent || '' };
            }""")
            if st["text"]:
                seen.append(len(st["text"]))
                if len(seen) >= 3 and seen[-1] > seen[0]:
                    break
        print("speech box growth:", seen[:8])
        if not seen:
            fails.append("her reply never appeared in the home speech box")

        # ── 3. it must speak Bangla, and never repeat itself ──
        await pg.wait_for_timeout(3000)
        spoken = await pg.evaluate("()=>window.__spoken")
        print("spoken lines:", len(spoken))
        for line in spoken[:6]:
            print("  speak:", line[:90])
        if not any(any('\u0980' <= ch <= '\u09ff' for ch in l) for l in spoken):
            fails.append("nothing was spoken in Bangla")
        if spoken and len(set(spoken)) != len(spoken):
            fails.append("the assistant spoke the same line twice (echo/repeat)")

        # ── 4. a real tool must actually execute ──
        res = await pg.evaluate("""async ()=>{
          const out = {};
          const a = await window.ZY.Tools.execute('write_file', { path: 'probe.txt', content: 'zyraxon probe' });
          out.write_file = a.ok;
          const b = await window.ZY.Tools.execute('read_file', { path: 'probe.txt' });
          out.read_file = b.ok && JSON.stringify(b.data).includes('zyraxon probe');
          const c = await window.ZY.Tools.execute('todo_write', { todos: [{ title: 'প্রোব টাস্ক', status: 'done' }] });
          out.todo_write = c.ok;
          return out;
        }""")
        print("real tools:", res)
        for k, v in res.items():
            if not v:
                fails.append(f"real tool failed: {k}")

        # ── 5. the console holds the transcript, and it has the user's line ──
        await pg.click("#btn-console")
        await pg.wait_for_timeout(600)
        posted = await pg.evaluate("""()=>{
          const rows=[...document.querySelectorAll('#chat .msg.user .body')];
          return rows.length ? rows[rows.length-1].textContent : '';
        }""")
        print("chat user message:", repr(posted))
        if USER_LINE not in posted:
            fails.append("the user's line never reached the chat transcript")

        await pg.screenshot(path="/tmp/home2.png")
        await b.close()
    print("FAILS:", fails if fails else "none")
    return 1 if fails else 0


raise SystemExit(asyncio.run(main()))
