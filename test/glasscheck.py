import asyncio
from playwright.async_api import async_playwright

CHECKS = """() => {
  const g = (id) => getComputedStyle(document.getElementById(id));
  const out = {};
  for (const id of ['model-btn','btn-settings','live-caption','composer-inner','orb-mic']) {
    const el = document.getElementById(id);
    if (!el) { out[id] = 'MISSING'; continue; }
    const cs = getComputedStyle(el);
    out[id] = { bg: cs.backgroundImage.slice(0,60), blur: cs.backdropFilter, border: cs.borderTopColor, r: cs.borderRadius };
  }
  const vb = document.getElementById('bg-video').getBoundingClientRect();
  out._video = { w: Math.round(vb.width), h: Math.round(vb.height) };
  out._orbBox = (() => { const r = document.getElementById('orb-btn').getBoundingClientRect(); return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) }; })();
  out._docH = document.documentElement.scrollHeight;
  out._winH = window.innerHeight;
  return out;
}"""


async def m():
    async with async_playwright() as p:
        b = await p.chromium.launch(args=['--no-sandbox', '--autoplay-policy=no-user-gesture-required'])
        ctx = await b.new_context(viewport={'width': 412, 'height': 915}, device_scale_factor=2)
        pg = await ctx.new_page()
        await pg.goto('http://127.0.0.1:12000/', wait_until='networkidle')
        await pg.wait_for_timeout(2500)
        import json
        print(json.dumps(await pg.evaluate(CHECKS), ensure_ascii=False, indent=1))
        await b.close()

asyncio.run(m())
