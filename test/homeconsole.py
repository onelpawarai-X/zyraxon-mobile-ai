"""Verify the home/console split actually works: every control is clickable,
the console opens, tabs switch, and a real message round-trips to the model.
"""
import asyncio
from playwright.async_api import async_playwright

BASE = "http://localhost:12000"


async def main():
    fails = []
    async with async_playwright() as p:
        b = await p.chromium.launch(args=["--no-sandbox", "--autoplay-policy=no-user-gesture-required"])
        ctx = await b.new_context(viewport={"width": 412, "height": 915}, device_scale_factor=2)
        pg = await ctx.new_page()
        errs = []
        pg.on("pageerror", lambda e: errs.append(str(e)))
        pg.on("console", lambda m: errs.append(m.text) if m.type == "error" else None)
        await pg.add_init_script("localStorage.setItem('zyraxon:settings', JSON.stringify({tts:false,automic:false}));")
        await pg.goto(BASE, wait_until="networkidle")
        await pg.wait_for_timeout(1500)

        # every visible control must be hit-testable (nothing covering it)
        hit = await pg.evaluate("""() => {
          const out = [];
          for (const id of ['orb-btn','btn-console','btn-settings','btn-menu','model-btn','char-chip']) {
            const el = document.getElementById(id);
            if (!el) { out.push([id,'missing']); continue; }
            const r = el.getBoundingClientRect();
            const top = document.elementFromPoint(r.left + r.width/2, r.top + r.height/2);
            out.push([id, el.contains(top) || top === el ? 'ok' : 'blocked-by:' + (top ? top.className || top.id : 'null')]);
          }
          return out;
        }""")
        print("hit-test:", hit)
        for i, st in hit:
            if st != "ok":
                fails.append(f"{i} {st}")

        # home shows only the orb — no chat/taskboard visible
        vis = await pg.evaluate("""() => ({
          chatVisible: !!document.querySelector('.cview[data-view=chat]').offsetParent,
          consoleHidden: document.getElementById('console').hidden,
          stageKids: document.getElementById('stage').children.length,
        })""")
        print("home:", vis)
        if not vis["consoleHidden"]:
            fails.append("console visible on load")
        if vis["stageKids"] != 1:
            fails.append(f"home stage has {vis['stageKids']} children (want 1)")

        # open console
        await pg.click("#btn-console")
        await pg.wait_for_timeout(500)
        if await pg.locator("#console").is_hidden():
            fails.append("console did not open")

        # tabs: the console now holds a single chat view. The tasks and tools
        # lists are their own docked panels, so they are checked there.
        on = await pg.evaluate("()=>document.querySelector('.cview[data-view=chat]').classList.contains('on')")
        if not on:
            fails.append("chat view did not activate")
        for view in ["chat"]:
            await pg.click(f'.ctab[data-tab="{view}"]')
            await pg.wait_for_timeout(200)
            on = await pg.evaluate(f"()=>document.querySelector('.cview[data-view={view}]').classList.contains('on')")
            if not on:
                fails.append(f"tab {view} did not activate")
        print("tabs ok")

        # real message round-trip
        await pg.fill("#input", "এক লাইনে বলো: আমি প্রস্তুত।")
        await pg.click("#btn-send")
        await pg.wait_for_timeout(20000)
        msgs = await pg.evaluate("()=>document.querySelectorAll('#chat .msg').length")
        txt = await pg.evaluate("()=>document.getElementById('chat').innerText.slice(0,200)")
        print("chat msgs:", msgs, "|", txt.replace("\n", " ")[:120])
        if msgs < 2:
            fails.append(f"expected user+ai bubbles, got {msgs}")

        # todo board renders when todos exist
        await pg.fill("#input", "আমার জন্য তিনটা টুডু বানাও: ওয়েবসাইট বানানো, পাবলিশ করা, ভেরিফাই করা")
        await pg.click("#btn-send")
        await pg.wait_for_timeout(30000)
        tb = await pg.evaluate("()=>({rows:document.querySelectorAll('#tb-list .tb-item').length, count:document.getElementById('tb-count').textContent})")
        print("todos:", tb)
        if tb["rows"] == 0:
            fails.append("no todo rows rendered")

        print("errors:", errs[:4])
        if errs:
            fails.append(f"js errors: {errs[:2]}")
        await b.close()

    print("FAILS:", fails if fails else "none")


asyncio.run(main())
