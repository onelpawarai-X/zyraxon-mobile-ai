"""Verify mid-task prompt injection: start a long job, inject a second
instruction while it runs, and confirm the first job keeps going AND the
injected instruction lands in the live context.
"""
import asyncio
from playwright.async_api import async_playwright

BASE = "http://localhost:12000"


async def main():
    fails = []
    async with async_playwright() as p:
        b = await p.chromium.launch(args=["--no-sandbox", "--autoplay-policy=no-user-gesture-required"])
        pg = await (await b.new_context(viewport={"width": 412, "height": 915})).new_page()
        errs = []
        pg.on("pageerror", lambda e: errs.append(str(e)))
        await pg.add_init_script("localStorage.setItem('zyraxon:settings', JSON.stringify({tts:false,automic:false}));")
        await pg.goto(BASE, wait_until="networkidle")
        await pg.wait_for_timeout(1200)
        await pg.click("#btn-console")
        await pg.wait_for_timeout(300)

        await pg.fill("#input", "একটা ওয়েবসাইট বানাও যেখানে তিনটা পেজ থাকবে, তারপর পাবলিশ করো।")
        await pg.click("#btn-send")
        await pg.wait_for_timeout(4000)

        busy = await pg.evaluate("()=>!!document.getElementById('btn-stop') && !document.getElementById('btn-stop').hidden")
        print("agent busy after 4s:", busy)

        await pg.fill("#input", "আর হ্যাঁ, সাইটে আমার নাম ZYRAXON যোগ করো।")
        await pg.click("#btn-send")
        await pg.wait_for_timeout(1500)
        toast = await pg.evaluate("()=>document.querySelector('#toast-host') ? document.querySelector('#toast-host').innerText : ''")
        print("inject toast:", toast[:80])

        # A full website build + publish takes a couple of minutes; give it
        # room so a slow-but-correct run is not mistaken for a failure.
        for _ in range(60):
            await pg.wait_for_timeout(5000)
            still = await pg.evaluate("()=>document.getElementById('btn-stop') && !document.getElementById('btn-stop').hidden")
            if not still:
                break
        await pg.wait_for_timeout(1500)

        res = await pg.evaluate("""() => ({
          msgs: document.querySelectorAll('#chat .msg').length,
          toolRows: document.querySelectorAll('#tl-list .tl-row').length,
          todos: document.querySelectorAll('#tb-list .tb-item').length,
          todoCount: document.getElementById('tb-count').textContent,
          hasInjected: document.getElementById('chat').innerText.includes('ZYRAXON যোগ'),
          artifact: document.getElementById('artifact-body').textContent.slice(0,80),
        })""")
        print("result:", res)

        if not busy:
            fails.append("agent never reported busy")
        if res["toolRows"] == 0:
            fails.append("no tool rows — tools did not run")
        if res["todos"] == 0:
            fails.append("no todos created")
        if not res["hasInjected"]:
            fails.append("injected prompt not present in chat")
        if errs:
            fails.append(f"js errors {errs[:2]}")
        await b.close()
    print("FAILS:", fails if fails else "none")


asyncio.run(main())
