import asyncio
from PIL import Image
from collections import Counter
from playwright.async_api import async_playwright


async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch(args=["--no-sandbox", "--autoplay-policy=no-user-gesture-required"])
        pg = await (await b.new_context(viewport={"width": 412, "height": 915}, device_scale_factor=2)).new_page()
        await pg.goto("http://localhost:12000", wait_until="networkidle")
        await pg.wait_for_timeout(2500)
        print("bbox", await pg.locator("#aura").bounding_box())
        await pg.locator("#aura").screenshot(path="/tmp/orb.png")
        print("bg", await pg.evaluate("()=>getComputedStyle(document.getElementById('aura')).backgroundImage.slice(0,110)"))
        print("filter", await pg.evaluate("()=>getComputedStyle(document.getElementById('aura')).filter"))
        await b.close()

    im = Image.open("/tmp/orb.png").convert("RGB")
    print("orb shot", im.size)
    w, h = im.size
    px = im.load()
    print(Counter(px[x, y] for y in range(0, h, 5) for x in range(0, w, 5)).most_common(6))


asyncio.run(main())
