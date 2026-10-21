"""End-to-end check of the ZYRAXON mobile assistant in a real browser.

Covers the behaviour the user asked for: home is only the orb plus one button,
chat streams, the todo card appears and the agent keeps working, tools really
run, no reasoning text leaks into the transcript, and the UI translates.
"""
import asyncio
from playwright.async_api import async_playwright

BASE = "https://work-1-xjvfsfgnuxzdemie.prod-runtime.all-hands.dev/"


async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch(args=[
            "--use-fake-ui-for-media-stream",
            "--use-fake-device-for-media-stream",
            "--autoplay-policy=no-user-gesture-required",
        ])
        ctx = await b.new_context(permissions=["microphone"], viewport={"width": 412, "height": 915})
        pg = await ctx.new_page()
        logs = []
        pg.on("console", lambda m: logs.append(f"{m.type}: {m.text}"))
        pg.on("pageerror", lambda e: logs.append("PAGEERROR: " + str(e)))
        await pg.goto(BASE, wait_until="networkidle")
        await pg.wait_for_timeout(3500)

        orb = await pg.query_selector("#orb-btn")
        feed = await pg.query_selector("#home-feed")
        cbtn = await pg.query_selector("#btn-console")
        print("ORB:", bool(orb), "| home-feed removed:", feed is None,
              "| chat btn:", bool(cbtn),
              "| lang:", await pg.evaluate("document.documentElement.lang"),
              "| title:", await pg.title())
        print("HOME TEXT:", repr(await pg.evaluate("document.querySelector('.voice-hall').innerText")))

        await pg.click("#btn-console")
        await pg.wait_for_timeout(700)
        await pg.fill("#input", "In one short line: who are you?")
        await pg.click("#btn-send")
        for _ in range(60):
            await pg.wait_for_timeout(500)
            busy = await pg.evaluate("!!(window.ZY && window.ZY.Agent && window.ZY.Agent.busy)")
            txt = await pg.evaluate("(()=>{const n=document.querySelectorAll('.msg.ai .body');return n.length?n[n.length-1].textContent:''})()")
            if txt and not busy:
                break
        print("USER MSG:", repr(await pg.evaluate("(()=>{const n=document.querySelectorAll('.msg.user .body');return n.length?n[n.length-1].textContent:''})()")))
        print("AI REPLY (last):", repr((await pg.evaluate("(()=>{const n=document.querySelectorAll('.msg.ai .body');return n.length?n[n.length-1].textContent:''})()"))[:400]))

        await pg.fill("#input", "Create a todo list with 2 steps to test tool calling, then start.")
        await pg.click("#btn-send")
        tb_visible, rows = False, 0
        for _ in range(70):
            await pg.wait_for_timeout(500)
            tb_visible = await pg.evaluate("(()=>{const t=document.getElementById('dock-todos');return t && !t.hidden;})()")
            rows = await pg.evaluate("document.querySelectorAll('#tb-list .tb-item').length")
            busy = await pg.evaluate("!!(window.ZY && window.ZY.Agent && window.ZY.Agent.busy)")
            if tb_visible and rows and not busy:
                break
        print("TASKBOARD visible:", tb_visible, "| rows:", rows)
        print("TODOS:", await pg.evaluate("[...document.querySelectorAll('#tb-list .tb-item')].map(e=>e.innerText)"))
        print("TOOL ROWS:", (await pg.evaluate("[...document.querySelectorAll('#tl-list .tl-row')].map(e=>e.innerText)"))[:6])
        print("home feed rows (must be 0):", await pg.evaluate("document.querySelectorAll('.hf-row').length"))

        # close the console sheet first — it covers the whole screen by design
        await pg.click("#console-close")
        await pg.wait_for_timeout(500)
        await pg.click("#btn-settings")
        await pg.wait_for_timeout(600)
        print("LANGUAGES:", await pg.evaluate("[...document.querySelectorAll('#set-ui-lang option')].map(o=>o.value)"))
        print("STT LANGS:", (await pg.evaluate("[...document.querySelectorAll('#set-stt-lang option')].map(o=>o.value)"))[:6])
        await pg.select_option("#set-ui-lang", "bn")
        await pg.wait_for_timeout(600)
        print("BN live:", repr(await pg.evaluate("document.querySelector('.voice-hall #live-text').textContent")))
        print("BN badge:", repr(await pg.evaluate("document.getElementById('live-badge').textContent")))
        print("BN chatbtn:", repr(await pg.evaluate("document.querySelector('#btn-console span').textContent")))
        print("BN drawer title:", repr(await pg.evaluate("document.getElementById('drawer-title').textContent")))
        print("BN todo title:", repr(await pg.evaluate("document.querySelector('#dock-todos .dock-head b').textContent")))
        await pg.select_option("#set-ui-lang", "en")
        await pg.wait_for_timeout(400)
        print("EN live after switch:", repr(await pg.evaluate("document.querySelector('.voice-hall #live-text').textContent")))

        print("CONSOLE ERRORS:", [l for l in logs if "error" in l.lower() or "PAGEERROR" in l][:8])
        await b.close()


asyncio.run(main())
