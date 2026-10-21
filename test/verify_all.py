"""Full verification of the user's requirements, end to end.

Checks, against the really running app:
  1. streaming is progressive (text grows before the turn ends)
  2. the voice speaks the answer but never the code or the tool names
  3. the code panel opens while code is still being written
  4. a website really gets built, published and opened
  5. a Linux desktop application really gets built
  6. the second model reports progress out loud
  7. the mic is always on and accepts several languages
"""
import asyncio, time
from playwright.async_api import async_playwright

BASE = "https://work-1-xjvfsfgnuxzdemie.prod-runtime.all-hands.dev/"
R = {}


async def wait_idle(pg, limit=400):
    for _ in range(limit):
        await pg.wait_for_timeout(500)
        if not await pg.evaluate("!!window.ZY.Agent.busy"):
            return True
    return False


async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch(args=["--use-fake-ui-for-media-stream", "--use-fake-device-for-media-stream"])
        ctx = await b.new_context(permissions=["microphone"], viewport={"width": 412, "height": 915})
        pg = await ctx.new_page()
        errs = []
        pg.on("pageerror", lambda e: errs.append("PAGEERROR " + str(e)))
        pg.on("console", lambda m: errs.append("console " + m.text) if m.type == "error" else None)
        await pg.add_init_script("""
          window.__spoken = [];
          window.__spokeAt = [];
          const hook = () => {
            if (!window.speechSynthesis) return;
            const orig = window.speechSynthesis.speak.bind(window.speechSynthesis);
            window.speechSynthesis.speak = (u) => { window.__spoken.push(u.text); window.__spokeAt.push(Date.now()); return orig(u); };
          };
          hook();
        """)
        await pg.goto(BASE, wait_until="networkidle")
        await pg.wait_for_timeout(3500)

        # ── 7. mic state ────────────────────────────────────────────────
        R["mic_always"] = await pg.evaluate("window.ZY.VOICE.STT.always")
        R["mic_auto_lang"] = await pg.evaluate("window.ZY.VOICE.STT.auto")
        R["mic_lang_empty"] = await pg.evaluate("window.ZY.VOICE.STT.lang") == ""

        await pg.click("#btn-console")
        await pg.wait_for_timeout(500)

        # ── 1 + 2. streaming and speech, no tools ───────────────────────
        await pg.evaluate("window.__spoken=[]; window.__spokeAt=[]")
        t0 = time.time()
        await pg.fill("#input", "In four sentences, explain why the sea is salty. No tools.")
        await pg.click("#btn-send")
        grew = []
        last = 0
        for _ in range(200):
            await pg.wait_for_timeout(200)
            # Count every visible assistant character: while the model reasons
            # the text lands in the "think" bubble, and the answer streams into
            # .body afterwards. Watching only .body reported 0 for any turn that
            # opened with reasoning, which looked like "no streaming".
            n = await pg.evaluate("""(()=>{
              const sel = '.msg.ai.streaming .body, .msg.ai.think .body, .msg.ai.live .body';
              let t = 0;
              document.querySelectorAll(sel).forEach((e) => { t += e.textContent.length; });
              return t;
            })()""")
            if n != last:
                grew.append((round(time.time() - t0, 2), n)); last = n
            if not await pg.evaluate("!!window.ZY.Agent.busy"):
                break
        end = round(time.time() - t0, 2)
        R["stream_steps"] = len(grew)
        R["stream_first_text_s"] = grew[0][0] if grew else None
        R["stream_end_s"] = end
        R["stream_progressive"] = len(grew) > 5 and grew[0][0] < end * 0.6
        spoken = await pg.evaluate("window.__spoken")
        spoke_at = await pg.evaluate("window.__spokeAt")
        R["spoken_chunks"] = len(spoken)
        R["speech_started_before_end"] = bool(spoke_at) and (spoke_at[0] - int(t0 * 1000)) / 1000 < end
        R["spoken_has_code"] = any("```" in s for s in spoken)

        # ── 3 + 4. build and publish a website ──────────────────────────
        await pg.evaluate("window.__spoken=[]; window.__spokeAt=[]")
        await pg.fill("#input", "Build me a small website about the moon and publish it. Show me the code.")
        await pg.click("#btn-send")
        code_seen_midway = False
        for _ in range(500):
            await pg.wait_for_timeout(400)
            if await pg.evaluate("(()=>{const a=document.getElementById('artifact');return a && !a.hidden && /Code/.test(document.getElementById('artifact-title').textContent)})()"):
                code_seen_midway = True
            if not await pg.evaluate("!!window.ZY.Agent.busy"):
                break
        R["code_panel_opened"] = await pg.evaluate("(()=>{const a=document.getElementById('artifact');return a && !a.hidden})()")
        R["code_panel_during_stream"] = code_seen_midway
        R["code_panel_title"] = await pg.evaluate("document.getElementById('artifact-title').textContent")
        R["tools_website"] = await pg.evaluate("[...new Set([...document.querySelectorAll('#tl-list .tl-name')].map(e=>e.textContent))]")
        R["site_published"] = await pg.evaluate("(()=>{const t=document.getElementById('artifact-title').textContent;return /^Live site/.test(t)?t:''})()")
        R["todos_website"] = await pg.evaluate("[...document.querySelectorAll('#tb-list .tb-item')].map(e=>e.innerText)")
        R["monitor_line"] = await pg.evaluate("(()=>{const m=document.getElementById('monitorbar');return m && !m.hidden ? document.getElementById('mon-text').textContent : ''})()")
        spoken2 = await pg.evaluate("window.__spoken")
        R["monitor_spoke"] = len(spoken2)
        R["spoken_has_tool_names"] = any(("todo_write" in s or "generate_file" in s or "publish_website" in s) for s in spoken2)

        # ── 5. a Linux desktop application ──────────────────────────────
        await pg.fill("#input", "Now build a small Linux desktop application in Python with Tkinter and run it to prove it works.")
        await pg.click("#btn-send")
        await wait_idle(pg)
        R["tools_linux"] = await pg.evaluate("[...new Set([...document.querySelectorAll('#tl-list .tl-name')].map(e=>e.textContent))]")
        R["shell_ran"] = await pg.evaluate("(()=>{const t=document.getElementById('artifact-title').textContent;return /shell/.test(t)?t:''})()")

        R["errors"] = errs[:6]
        for k, v in R.items():
            print(f"{k}: {v}")
        await b.close()


asyncio.run(main())
