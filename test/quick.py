import asyncio
from playwright.async_api import async_playwright


async def m():
    async with async_playwright() as p:
        b = await p.chromium.launch(args=['--no-sandbox', '--autoplay-policy=no-user-gesture-required'])
        ctx = await b.new_context(viewport={'width': 412, 'height': 915}, device_scale_factor=2)
        pg = await ctx.new_page()
        errs = []
        pg.on('console', lambda x: errs.append(x.text) if x.type == 'error' else None)
        pg.on('pageerror', lambda e: errs.append(str(e)))
        await pg.goto('http://127.0.0.1:12000/', wait_until='networkidle')
        await pg.wait_for_timeout(3500)
        await pg.screenshot(path='/tmp/ui1.png')
        print('errors:', errs[:5])
        print('bg scene:', await pg.evaluate("window.ZY.BG.current"))
        print('video on:', await pg.evaluate("document.getElementById('bg-video').classList.contains('on')"))
        print('video w/h:', await pg.evaluate("[document.getElementById('bg-video').videoWidth, document.getElementById('bg-video').videoHeight]"))
        print('orb btn:', await pg.locator('#orb-btn').count(), 'orb-mic:', await pg.locator('#orb-mic').count())
        print('attach btn:', await pg.locator('#btn-attach').count())
        await b.close()

asyncio.run(m())
