import asyncio, re
from playwright.async_api import async_playwright

URL = 'https://pixabay.com/videos/download/video-365651_medium.mp4'


async def m():
    async with async_playwright() as p:
        b = await p.chromium.launch(args=['--no-sandbox'])
        ctx = await b.new_context(user_agent='Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36')
        pg = await ctx.new_page()
        await pg.goto(URL, wait_until='domcontentloaded', timeout=60000)
        await pg.wait_for_timeout(5000)
        html = await pg.content()
        for u in re.findall(r'https?://[^"\'<> ]*\.(?:mp4|webm)[^"\'<> ]*', html)[:10]:
            print('MP4:', u)
        for u in re.findall(r'https?://cdn\.pixabay\.com/[^"\'<> ]+', html)[:10]:
            print('CDN:', u)
        await b.close()

asyncio.run(m())
