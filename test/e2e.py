"""ZYRAXON end-to-end verification.

Drives the real app in a real Chromium: loads the UI, asks for a task that
requires tool calls, and asserts the tools actually executed (todo board,
tool run log, published artifact) — not just that text appeared.
"""
import asyncio, json, sys, os
from playwright.async_api import async_playwright

BASE = os.environ.get("ZYRAXON_BASE_URL", "http://localhost:12000")
PROMPT = "একটা ছোট ওয়েবসাইট বানিয়ে পাবলিশ করো যেখানে লেখা থাকবে হ্যালো জাইরাক্সন।"

async def main():
    failures, notes = [], []
    try:
        await run(failures, notes)
    finally:
        print("── NOTES ──")
        for n in notes: print(" •", n)
        if failures:
            print("── FAIL ──")
            for f in failures: print(" •", f)
    if failures: sys.exit(1)
    print("ALL CHECKS PASSED")

async def run(failures, notes):
    async with async_playwright() as p:
        browser = await p.chromium.launch(args=["--no-sandbox", "--autoplay-policy=no-user-gesture-required"])
        ctx = await browser.new_context(viewport={"width": 412, "height": 915}, device_scale_factor=2,
                                        permissions=["clipboard-read", "clipboard-write", "notifications"])
        page = await ctx.new_page()
        errors = []
        page.on("console", lambda m: errors.append(m.text) if m.type == "error" else None)
        page.on("pageerror", lambda e: errors.append(str(e)))

        # start with TTS off so headless has no dangling speech
        await page.add_init_script("localStorage.setItem('zyraxon:settings', JSON.stringify({tts:false,automic:false}));")
        await page.goto(BASE, wait_until="networkidle")
        await page.wait_for_timeout(1500)

        # 1. shell rendered
        title = await page.title()
        notes.append(f"title={title!r}")
        if await page.locator("#aura").count() != 1: failures.append("aura orb missing")
        if await page.locator("#orb-btn").count() != 1: failures.append("orb mic button missing")

        # 2. models loaded into the picker (fastest first)
        await page.click("#model-btn")
        await page.wait_for_timeout(400)
        items = await page.locator(".model-item").count()
        first = await page.locator(".model-item").first.inner_text() if items else ""
        notes.append(f"models={items} first={first.splitlines()[0] if first else ''!r}")
        if items < 5: failures.append(f"model menu only had {items} entries")
        await page.keyboard.press("Escape")

        # 3. drive a real multi-step task (console holds the chat box now)
        await page.click("#btn-console")
        await page.wait_for_timeout(600)
        await page.fill("#input", PROMPT)
        await page.click("#btn-send")

        # wait for tool activity
        got_tool = False
        for _ in range(240):
            await page.wait_for_timeout(1000)
            rows = await page.locator("#tl-list .tl-row").count()
            if rows > 0:
                got_tool = True
            busy = not await page.locator("#btn-stop").is_hidden()
            if got_tool and not busy:
                break
        notes.append(f"tool_rows={await page.locator('#tl-list .tl-row').count()}")

        # 4. assertions on real effects
        if not got_tool: failures.append("no tool call executed")

        # The todo board is its own docked panel now (#dock-todos), not a console
        # tab. Read the persisted data, the panel counter, and the rendered rows.
        todos = await page.evaluate("()=>JSON.parse(localStorage.getItem('zyraxon:settings')||'{}').todos||[]")
        tab_count = await page.locator("#tb-count").inner_text()
        notes.append(f"todos={len(todos)} tab_count={tab_count}")
        if not todos: failures.append("todo board never appeared")
        rows = await page.locator("#tb-list .tb-item").count()
        notes.append(f"todo_rows={rows}")
        if rows != len(todos): failures.append(f"todo rows {rows} != {len(todos)} stored")

        names = await page.locator("#tl-list .tl-row .tl-name").all_inner_texts()
        notes.append(f"tools_used={names}")
        if "publish_website" not in names: failures.append("publish_website was never really called")
        errs = await page.locator("#tl-list .tl-row.err").count()
        err_notes = await page.locator("#tl-list .tl-row.err .tl-res").all_inner_texts()
        notes.append(f"tool_errors={errs} {err_notes}")

        art = await page.locator("#artifact").is_visible()
        art_text = (await page.locator("#artifact-body").inner_text())[:160] if art else ""
        notes.append(f"artifact_visible={art} body={art_text!r}")

        # 5. verify the published site really serves
        url = None
        for t in await page.locator("#tl-list .tl-row .tl-res").all_inner_texts():
            if "http" in t: url = t.split("http", 1)[1]; url = "http" + url.split()[0]
        if url:
            status = await page.evaluate("async (u) => (await fetch(u)).status", url)
            notes.append(f"published_url={url} status={status}")
            if status != 200: failures.append(f"published site returned {status}")
        else:
            notes.append("published_url: not surfaced in tool summary (checking artifact)")
            if not art: failures.append("no published URL and no artifact")

        await page.screenshot(path="/tmp/zyraxon-e2e.png", full_page=False)
        await page.screenshot(path="/tmp/zyraxon-e2e-full.png", full_page=True)

        # 6. character switch works and persists (console is a modal sheet,
        # so close it first — exactly what a user does)
        await page.click("#console-close")
        await page.wait_for_timeout(300)
        await page.click("#btn-settings")
        await page.wait_for_timeout(500)
        await page.click("[data-nav='characters']")
        await page.wait_for_timeout(400)
        await page.click(".char-card:nth-child(3)")
        await page.wait_for_timeout(700)
        cname = await page.locator("#char-chip-name").inner_text()
        stored = await page.evaluate("localStorage.getItem('zyraxon:settings')")
        notes.append(f"character={cname} settings={stored}")
        if "বন্ধু" not in cname: failures.append(f"character switch failed ({cname})")

        real_errors = [e for e in errors if "favicon" not in e.lower()]
        notes.append(f"console_errors={real_errors[:6]}")
        await browser.close()

    print("── NOTES ──")
    for n in notes: print(" •", n)
    print("── RESULT ──")
    if failures:
        print("FAIL:")
        for f in failures: print(" •", f)
        sys.exit(1)
    print("ALL CHECKS PASSED")

asyncio.run(main())
