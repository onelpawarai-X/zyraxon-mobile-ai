"""Drive the real assistant the way the user does: ask for a website, then for
a desktop application, and record everything that really happened — tool calls,
todo board, code panel, and what the voice was asked to read aloud.
"""
import asyncio, json
from playwright.async_api import async_playwright

BASE = "https://work-1-xjvfsfgnuxzdemie.prod-runtime.all-hands.dev/"


async def run(pg, prompt, label, seconds=180):
    print(f"\n{'='*70}\n>>> {label}: {prompt}\n{'='*70}")
    await pg.fill("#input", prompt)
    await pg.click("#btn-send")
    seen_tools = set()
    for _ in range(seconds * 2):
        await pg.wait_for_timeout(500)
        tools = await pg.evaluate("[...document.querySelectorAll('#tl-list .tl-row .tl-name')].map(e=>e.textContent)")
        seen_tools |= set(tools)
        busy = await pg.evaluate("!!(window.ZY && window.ZY.Agent && window.ZY.Agent.busy)")
        if not busy:
            break
    print("TOOLS USED:", sorted(seen_tools))
    print("TODOS:", await pg.evaluate("[...document.querySelectorAll('#tb-list .tb-item')].map(e=>e.innerText)"))
    print("SPOKEN (what TTS was told to say):", await pg.evaluate("window.__spoken || []"))
    print("ARTIFACT OPEN:", await pg.evaluate("(()=>{const a=document.getElementById('artifact');return a && !a.hidden;})()"))
    print("ARTIFACT TITLE:", await pg.evaluate("document.getElementById('artifact-title').textContent"))
    print("ARTIFACT BODY:", (await pg.evaluate("document.getElementById('artifact-body').textContent"))[:300])
    print("FILES ON SERVER:", await pg.evaluate("fetch('/api/tree').then(r=>r.json()).then(j=>JSON.stringify(j).slice(0,600))"))


async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch(args=["--use-fake-ui-for-media-stream", "--use-fake-device-for-media-stream"])
        ctx = await b.new_context(permissions=["microphone"], viewport={"width": 412, "height": 915})
        pg = await ctx.new_page()
        errs = []
        pg.on("pageerror", lambda e: errs.append(str(e)))
        pg.on("console", lambda m: errs.append("console:" + m.text) if m.type == "error" else None)
        # capture everything the TTS layer is asked to speak
        await pg.add_init_script("""
          window.__spoken = [];
          const hook = () => {
            if (!window.speechSynthesis) return;
            const orig = window.speechSynthesis.speak.bind(window.speechSynthesis);
            window.speechSynthesis.speak = (u) => { window.__spoken.push(u.text); return orig(u); };
          };
          hook();
        """)
        await pg.goto(BASE, wait_until="networkidle")
        await pg.wait_for_timeout(3500)
        await pg.evaluate("window.__spoken = []")
        await pg.click("#btn-console")
        await pg.wait_for_timeout(500)

        await run(pg, "Build me a complete website about space travel and publish it. Show me the code.",
                  "WEBSITE", seconds=180)
        await run(pg, "Now build me a Linux desktop application with a window. Use Python and Tkinter.",
                  "LINUX APP", seconds=180)

        print("\nERRORS:", errs[:10])
        await b.close()


asyncio.run(main())
