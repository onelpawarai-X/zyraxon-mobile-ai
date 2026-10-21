"""Is the reply really streamed token-by-token, or dumped at the end?

The user's complaint was that output only appears when the work is finished.
So this records the wall-clock time of every UI update during one real turn and
checks the text grew in several distinct steps spread over time, rather than
appearing in a single jump.
"""
import asyncio

from playwright.async_api import async_playwright

BASE = "http://localhost:12000"


async def main():
    fails = []
    async with async_playwright() as p:
        b = await p.chromium.launch(args=["--no-sandbox"])
        ctx = await b.new_context(viewport={"width": 412, "height": 915})
        pg = await ctx.new_page()
        pg.on("pageerror", lambda e: fails.append(f"js error: {e}"))
        await pg.goto(BASE, wait_until="networkidle")
        await pg.wait_for_timeout(2500)

        # stamp every stream update with the time and the text length
        await pg.evaluate("""()=>{
          window.__ticks = [];
          const U = window.ZY.ui;
          const orig = U.streamToken.bind(U);
          U.streamToken = (t) => { window.__ticks.push([Date.now(), (U._streamText||'').length + t.length]); return orig(t); };
        }""")

        await pg.evaluate("()=>window.ZY.App.submit('বাংলায় পাঁচ লাইনে বুঝিয়ে বলো তুমি কে এবং কী কী করতে পারো।')")

        for _ in range(60):
            await pg.wait_for_timeout(500)
            done = await pg.evaluate("()=>!window.ZY.Agent.busy")
            if done:
                break

        ticks = await pg.evaluate("()=>window.__ticks")
        print(f"stream updates: {len(ticks)}")
        if ticks:
            t0 = ticks[0][0]
            lengths = [l for _, l in ticks]
            times = [round((t - t0) / 1000, 2) for t, _ in ticks]
            print("first 12 updates (s, chars):", list(zip(times[:12], lengths[:12])))
            print("last update:", times[-1], "s,", lengths[-1], "chars")
            growths = [lengths[i] - lengths[i - 1] for i in range(1, len(lengths))]
            print("distinct growth steps:", len(set(growths)))
            if len(ticks) < 4:
                fails.append(f"only {len(ticks)} stream updates — output is dumped, not streamed")
            if len(set(growths)) < 2:
                fails.append("every update had identical size — not real token streaming")
            # A buffered implementation shows the full answer in one go. Real
            # streaming was observable if the UI held strictly partial text for
            # a while before the final length arrived.
            final = lengths[-1]
            partial = [l for l in lengths if l < final]
            if final > 60 and len(partial) < 3:
                fails.append("no intermediate partial states — reply was dumped, not streamed")

        # The reply lives in the chat sheet, so open it the way a user does.
        await pg.click('#btn-console')
        await pg.wait_for_timeout(600)
        final = await pg.evaluate("""()=>{
          const rows=[...document.querySelectorAll('#chat .msg.ai .body')];
          return rows.length? rows[rows.length-1].textContent : '';
        }""")
        print("final reply chars:", len(final))
        print("final reply:", final[:160].replace("\n", " "))
        if len(final) < 40:
            fails.append("assistant reply is empty or truncated on the home screen")

        await b.close()
    print("FAILS:", fails if fails else "none")
    return 1 if fails else 0


raise SystemExit(asyncio.run(main()))
