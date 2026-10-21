"""ZYRAXON long-term memory verification.

The user's requirement: everything must be remembered — past conversation,
facts, todos — locally, and still be there after the app is closed and
reopened (or after the cloud is gone).

Drives the real app: writes a fact, reloads the page (the app-restart
equivalent), and asserts the transcript, the fact and the todo board all
come back from the local DB.
"""
import asyncio, os, sys
from playwright.async_api import async_playwright

BASE = os.environ.get("ZYRAXON_BASE_URL", "http://localhost:12000")
FACT_KEY = "প্রিয়_রঙ"
FACT_VAL = "গাঢ় নীল আর সোনালি"

async def main():
    failures, notes = [], []
    async with async_playwright() as p:
        b = await p.chromium.launch(args=["--no-sandbox"])
        ctx = await b.new_context(viewport={"width": 412, "height": 915})
        page = await ctx.new_page()
        errs = []
        page.on("console", lambda m: errs.append(m.text) if m.type == "error" else None)
        page.on("pageerror", lambda e: errs.append(str(e)))
        await page.add_init_script("localStorage.setItem('zyraxon:settings', JSON.stringify({tts:false,automic:false,greeted:true}));")
        await page.goto(BASE, wait_until="domcontentloaded")
        await page.wait_for_timeout(1800)

        # ── session 1: hold a conversation and store a durable fact ──
        # The composer is inside the console sheet, so open it first.
        await page.click("#btn-console")
        await page.wait_for_selector("#input", state="visible", timeout=10000)
        await page.fill("#input", "আমার একটা ছোট ওয়েবসাইট বানিয়ে পাবলিশ করো, লেখা থাকবে শুভ রাত্রি।")
        await page.click("#btn-send")
        # wait for the run to actually start, then to actually finish
        for _ in range(60):
            await page.wait_for_timeout(500)
            if not await page.locator("#btn-stop").is_hidden():
                break
        for _ in range(240):
            await page.wait_for_timeout(1000)
            if await page.locator("#btn-stop").is_hidden():
                break
        await page.wait_for_timeout(1500)

        # write a fact through the real memory tool, like the agent would
        await page.evaluate(f"window.ZY.Tools.execute('memory_write', {{key: '{FACT_KEY}', value: '{FACT_VAL}', tag: 'preference'}})")
        await page.wait_for_timeout(400)

        before_msgs = await page.locator(".msg .body").count()
        before_todos = await page.evaluate("(window.ZY.store.get('todos')||[]).length")
        before_user = await page.locator(".msg.user .body").all_inner_texts()
        notes.append(f"session1 msgs={before_msgs} todos={before_todos}")
        if before_msgs < 2: failures.append("session 1 produced no conversation to remember")

        # ── simulate app restart: fresh page, same origin storage ──
        await page.reload(wait_until="domcontentloaded")
        await page.wait_for_timeout(2500)

        after_msgs = await page.locator(".msg .body").count()
        after_user = await page.locator(".msg.user .body").all_inner_texts()
        notes.append(f"session2 msgs={after_msgs} user_msgs={after_user}")
        # tool-only assistant turns carry no text and are intentionally not shown
        if after_msgs < 3:
            failures.append(f"history not restored after restart ({before_msgs} -> {after_msgs})")
        if not any("শুভ রাত্রি" in m for m in after_user):
            failures.append("the earlier user message did not come back after restart")

        # the fact must be recalled from the local DB
        mem = await page.evaluate(f"window.ZY.DB.recall('{FACT_KEY}')")
        notes.append(f"recalled={mem}")
        if not mem or mem[0]["value"] != FACT_VAL:
            failures.append("durable fact lost after restart")

        # the todo board must come back too (it is the docked #dock-todos panel
        # now; the old #taskboard element is gone)
        todos = await page.evaluate("(window.ZY.store.get('todos')||[]).map(t=>t.title+':'+t.status)")
        board_visible = await page.evaluate("()=>{const d=document.getElementById('dock-todos'); return !!d && !d.hidden;}")
        notes.append(f"todos_after={todos} board_visible={board_visible}")
        if before_todos and not todos: failures.append("todo board lost after restart")
        if todos and not board_visible: failures.append("todo board not re-rendered after restart")

        # the agent's own context must contain the earlier turns (it really remembers)
        hist = await page.evaluate("window.ZY.Agent.history.map(m=>m.role+':'+(m.content||'').slice(0,40))")
        notes.append(f"agent_history={hist[:6]}")
        if not any(h.startswith("user:") for h in hist):
            failures.append("agent context empty after restart — it would not remember")

        # and the system prompt must carry the stored memory
        sysprompt = await page.evaluate("window.ZY.Agent.buildSystem()")
        if FACT_VAL not in sysprompt:
            failures.append("stored fact not injected into the system prompt")
        notes.append(f"fact_in_system_prompt={FACT_VAL in sysprompt}")

        errs = [e for e in errs if "favicon" not in e.lower()]
        notes.append(f"console_errors={errs[:5]}")
        if errs: failures.append(f"console errors: {errs[:3]}")

        await page.screenshot(path="/tmp/zyraxon-memory.png", full_page=True)
        await b.close()

    print("── NOTES ──")
    for n in notes: print(" •", n)
    print("── RESULT ──")
    if failures:
        for f in failures: print(" •", f)
        sys.exit(1)
    print("LONG-TERM MEMORY VERIFIED")

asyncio.run(main())
