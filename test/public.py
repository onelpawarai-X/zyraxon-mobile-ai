"""Verify the LIVE public URL the user will open, not just localhost.

Checks the page loads with no JS errors, the API proxy really answers, the home
feed works, and the real model responds over the public origin.
"""
import asyncio

from playwright.async_api import async_playwright

PUB = "https://work-1-xjvfsfgnuxzdemie.prod-runtime.all-hands.dev/"


async def main():
    fails = []
    async with async_playwright() as p:
        b = await p.chromium.launch(args=["--no-sandbox"])
        ctx = await b.new_context(viewport={"width": 412, "height": 915})
        pg = await ctx.new_page()
        pg.on("pageerror", lambda e: fails.append(f"js error: {e}"))
        r = await pg.goto(PUB, wait_until="networkidle", timeout=60000)
        print("http status:", r.status)
        await pg.wait_for_timeout(3000)

        boot = await pg.evaluate("""()=>({
          zy: typeof window.ZY, agent: typeof window.ZY.Agent,
          models: (window.ZY.App && window.ZY.App.models || []).length,
          orb: !!document.getElementById('orb-btn'),
          feed: !!document.getElementById('home-feed'),
          proc: !!document.getElementById('proc-strip'),
        })""")
        print("boot:", boot)
        if boot["zy"] != "object" or boot["agent"] != "object":
            fails.append("app did not boot")
        if not boot["models"]:
            fails.append("no models loaded from the public origin")
        if not boot["feed"]:
            fails.append("home feed missing")

        # the model list must come through the public proxy
        ids = await pg.evaluate("()=>window.ZY.App.models.map(m=>m.id)")
        print("models:", ids[:4], "...", len(ids), "total")
        if len(ids) < 5:
            fails.append("model catalogue is short")

        # a real end-to-end turn over the public URL
        await pg.evaluate("()=>window.ZY.VOICE.STT.onFinal('এক লাইনে বলো তুমি কে')")
        reply = ""
        for _ in range(50):
            await pg.wait_for_timeout(600)
            reply = await pg.evaluate("""()=>{
              const r=[...document.querySelectorAll('#hf-list .hf-row.ai .hf-bubble')];
              return r.length? r[r.length-1].textContent : '';
            }""")
            if reply and not await pg.evaluate("()=>window.ZY.Agent.busy"):
                break
        print("reply chars:", len(reply))
        print("reply:", reply[:110].replace("\n", " "))
        if len(reply) < 15:
            fails.append("no real reply over the public URL")

        await pg.screenshot(path="/tmp/public_home.png")
        await b.close()
    print("FAILS:", fails if fails else "none")
    return 1 if fails else 0


raise SystemExit(asyncio.run(main()))
