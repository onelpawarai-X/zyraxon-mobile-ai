"""Read back what the LIVE public app actually shows on the home feed."""
import asyncio

from playwright.async_api import async_playwright

PUB = "https://work-1-xjvfsfgnuxzdemie.prod-runtime.all-hands.dev/"


async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch(args=["--no-sandbox"])
        ctx = await b.new_context(viewport={"width": 412, "height": 915})
        pg = await ctx.new_page()
        await pg.goto(PUB, wait_until="networkidle", timeout=60000)
        await pg.wait_for_timeout(3500)

        await pg.evaluate("()=>window.ZY.VOICE.STT.onFinal('এক লাইনে বলো তুমি কে')")
        for _ in range(50):
            await pg.wait_for_timeout(600)
            if not await pg.evaluate("()=>window.ZY.Agent.busy"):
                break
        await pg.wait_for_timeout(1200)

        rows = await pg.evaluate("""()=>[...document.querySelectorAll('#hf-list .hf-row')].map(r=>({
          who: r.className.includes('ai')?'ai':'me',
          text: r.querySelector('.hf-bubble').textContent.trim().slice(0,90)
        }))""")
        print("home feed rows:", len(rows))
        for r in rows:
            print(" ", r["who"], "|", r["text"])
        geo = await pg.evaluate("""()=>{
          const f=document.getElementById('home-feed');
          const o=document.getElementById('orb-btn').getBoundingClientRect();
          return {visible: !f.hidden && f.offsetHeight>0, feedH: Math.round(f.getBoundingClientRect().height),
                  orbH: Math.round(o.height), orbTop: Math.round(o.top),
                  viewport: innerHeight, overflow: document.body.scrollHeight > innerHeight};
        }""")
        print("geometry:", geo)
        await pg.screenshot(path="/tmp/home_live.png")
        await b.close()


asyncio.run(main())
