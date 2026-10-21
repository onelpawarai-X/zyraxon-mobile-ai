"""Measure realtime-ness: does text appear progressively while streaming,
and does the voice start before the answer is finished?
"""
import asyncio, time
from playwright.async_api import async_playwright

BASE = "https://work-1-xjvfsfgnuxzdemie.prod-runtime.all-hands.dev/"


async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch()
        ctx = await b.new_context(viewport={"width": 412, "height": 915})
        pg = await ctx.new_page()
        await pg.add_init_script("""
          window.__spoken = [];
          window.__spokenAt = [];
          if (window.speechSynthesis) {
            const orig = window.speechSynthesis.speak.bind(window.speechSynthesis);
            window.speechSynthesis.speak = (u) => { window.__spoken.push(u.text); window.__spokenAt.push(Date.now()); return orig(u); };
          }
        """)
        await pg.goto(BASE, wait_until="networkidle")
        await pg.wait_for_timeout(3000)
        await pg.evaluate("window.__spoken = []; window.__spokenAt = []")
        await pg.click("#btn-console")
        await pg.wait_for_timeout(400)

        t0 = time.time()
        await pg.fill("#input", "Tell me in five sentences why the sky is blue. Do not use any tools.")
        await pg.click("#btn-send")

        growth = []
        last_len = 0
        for _ in range(200):
            await pg.wait_for_timeout(200)
            n = await pg.evaluate("(()=>{const e=document.querySelector('.msg.ai.streaming .body');return e?e.textContent.length:0})()")
            if n != last_len:
                growth.append((round(time.time() - t0, 2), n))
                last_len = n
            if not await pg.evaluate("!!window.ZY.Agent.busy"):
                break
        total = round(time.time() - t0, 2)
        print("TOKEN GROWTH (t, chars):", growth[:14], "..." if len(growth) > 14 else "")
        print("distinct growth steps:", len(growth), "| total time:", total, "s")
        if growth:
            print("first text at:", growth[0][0], "s  |  last text at:", growth[-1][0], "s")

        spoken = await pg.evaluate("window.__spoken")
        spoken_at = await pg.evaluate("window.__spokenAt")
        print("SPOKEN COUNT:", len(spoken))
        for i, s in enumerate(spoken[:6]):
            print(f"   +{round((spoken_at[i]-int(t0*1000))/1000, 2)}s {s[:80]!r}")
        if spoken_at:
            print("first speech at:", round((spoken_at[0] - int(t0 * 1000)) / 1000, 2), "s (answer ended at", total, "s)")
        print("REPLY:", (await pg.evaluate("(()=>{const n=document.querySelectorAll('.msg.ai .body');return n[n.length-1].textContent})()"))[:400])
        await b.close()


asyncio.run(main())
