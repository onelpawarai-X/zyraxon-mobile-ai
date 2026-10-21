"""End-to-end: does the voice really start while the answer is still arriving?

Sends a real prompt through the agent, records the moment the first spoken
sentence is queued, and compares it with the moment the stream ends. If the
first speech only happens at the end, the gap is ~0 and the test fails.
"""
import asyncio
from playwright.async_api import async_playwright

BASE = "http://127.0.0.1:12000/"


async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch()
        pg = await (await b.new_context()).new_page()
        await pg.goto(BASE, wait_until="networkidle")
        await pg.wait_for_timeout(2500)
        # Record every real push to the speech queue with a timestamp.
        await pg.evaluate("""(() => {
          window.__spoken = [];
          const T = window.ZY.VOICE.TTS;
          const orig = T.push;
          T.push = function (t) { window.__spoken.push({ t: String(t), at: performance.now() }); return orig.apply(T, arguments); };
          // When did the first real content token arrive from the provider?
          window.__firstTok = 0;
          const feed = T.feed;
          T.feed = function (txt) { if (!window.__firstTok && String(txt).trim()) window.__firstTok = performance.now(); return feed.apply(T, arguments); };
          window.__streamEnd = 0;
          const os = window.ZY.Agent._stream;
          window.ZY.Agent._stream = async function () { const r = await os.apply(this, arguments); window.__streamEnd = performance.now(); return r; };
        })()""")
        await pg.evaluate("""window.ZY.Agent.send('এক থেকে পাঁচ পর্যন্ত গুনে বলো, প্রতিটি আলাদা লাইনে।')""")
        await pg.wait_for_timeout(25000)
        res = await pg.evaluate("""(() => {
          const s = window.__spoken.filter(x => x.t.trim().length);
          return { first: s.length ? s[0].at : 0, tok: window.__firstTok, end: window.__streamEnd, count: s.length, sample: s.slice(0,3).map(x=>x.t) };
        })()""")
        print(res)
        if not res["count"]:
            print("FAIL nothing was ever spoken")
            return
        if res["end"] and res["first"] < res["end"] - 300:
            print(f"PASS speech started {int(res['end']-res['first'])}ms BEFORE the stream ended")
        else:
            print(f"FAIL speech only started at/after the end (first={res['first']:.0f} end={res['end']:.0f})")
        if res["tok"] and res["first"]:
            lag = res["first"] - res["tok"]
            print(f"{'PASS' if lag < 2500 else 'FAIL'} speech lags the first token by {int(lag)}ms")
        await b.close()

asyncio.run(main())
