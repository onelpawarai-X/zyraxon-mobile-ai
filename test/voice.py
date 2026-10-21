"""ZYRAXON voice + attachment verification.

Drives the real handlers the app installs: interim speech must land in the
composer, a final phrase must auto-send, the speech box must mirror what is
spoken, and an attached image must reach the model as a real content part.
"""
import asyncio, base64, json, os, sys
from playwright.async_api import async_playwright

BASE = os.environ.get("ZYRAXON_BASE_URL", "http://127.0.0.1:12000")
PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAJUlEQVR42u3NMQEAAAgDoC251a3g"
    "LwSgcyfTVFVVVVVVVVVVVVX1twV1qgP9kQ2m4QAAAABJRU5ErkJggg=="
)

FAILS, NOTES = [], []


async def run():
    async with async_playwright() as p:
        b = await p.chromium.launch(args=["--no-sandbox", "--autoplay-policy=no-user-gesture-required"])
        ctx = await b.new_context(viewport={"width": 412, "height": 915}, device_scale_factor=2)
        page = await ctx.new_page()
        errs = []
        page.on("console", lambda m: errs.append(m.text) if m.type == "error" else None)
        page.on("pageerror", lambda e: errs.append(str(e)))
        await page.add_init_script("localStorage.setItem('zyraxon:settings', JSON.stringify({tts:false,automic:false,autosend:true}));")
        await page.goto(BASE, wait_until="networkidle")
        await page.wait_for_timeout(2500)

        # ── 1. the orb IS the mic: no separate mic button exists ──
        NOTES.append(f"orb_btn={await page.locator('#orb-btn').count()} old_mic_btn={await page.locator('#btn-mic').count()}")
        if await page.locator("#orb-btn").count() != 1: FAILS.append("orb mic button missing")
        if await page.locator("#btn-mic").count() != 0: FAILS.append("old mic button still present")

        # ── 2. interim speech lands in the composer ──
        phrase = "আমার ফেসবুকে একজনকে মেসেজ দাও"
        await page.evaluate("(t) => window.ZY.VOICE.STT.onPartial(t)", phrase)
        await page.wait_for_timeout(200)
        val = await page.input_value("#input")
        NOTES.append(f"interim_in_input={val!r}")
        if val.strip() != phrase: FAILS.append(f"interim text did not reach composer (got {val!r})")
        live = await page.inner_text("#live-text")
        if phrase not in live: FAILS.append("interim text did not reach live caption")

        # ── 3. final phrase auto-sends (no button press) ──
        await page.evaluate("(t) => window.ZY.VOICE.STT.onFinal(t)", phrase)
        await page.wait_for_timeout(1200)
        cleared = await page.input_value("#input")
        msgs = await page.locator("#chat .msg.user").all_inner_texts()
        NOTES.append(f"input_cleared={cleared == ''} user_msgs={len(msgs)} first={msgs[0][:40] if msgs else ''!r}")
        if cleared != "": FAILS.append("composer not cleared after final speech")
        if not any(phrase in m for m in msgs): FAILS.append("spoken phrase never auto-sent as a user message")

        # Let the turn settle so it does not fight the attachment test. The
        # stop button can still be hidden in the first instant after submit,
        # so wait for it to appear before waiting for it to go away.
        for _ in range(40):
            await page.wait_for_timeout(250)
            if await page.locator("#btn-stop").is_visible(): break
        for _ in range(120):
            await page.wait_for_timeout(500)
            if await page.locator("#btn-stop").is_hidden(): break

        # ── 4. speech box mirrors what the assistant says ──
        await page.evaluate("window.ZY.ui.setSpeak('টেস্ট: আমি বলছি এখন।', true)")
        await page.wait_for_timeout(250)
        if await page.locator("#speak-box").is_hidden(): FAILS.append("speech box did not appear")
        sb = await page.inner_text("#speak-text")
        NOTES.append(f"speak_box={sb!r}")
        if "আমি বলছি এখন" not in sb: FAILS.append("speech box text wrong")
        await page.evaluate("document.getElementById('speak-box').hidden = true")

        # ── 5. an attached image reaches the model as a real content part ──
        # The composer lives in the chat sheet, so open it the way a user does.
        await page.click("#btn-console")
        await page.wait_for_timeout(600)
        await page.set_input_files("#file-input", {"name": "ছবি.png", "mimeType": "image/png", "buffer": PNG})
        await page.wait_for_timeout(600)
        tray = await page.locator("#attach-tray .att").count()
        NOTES.append(f"attach_tiles={tray}")
        if tray != 1: FAILS.append(f"attachment tile not shown (got {tray})")
        await page.fill("#input", "এই ছবিতে কী আছে দেখো")
        await page.click("#btn-send")
        await page.wait_for_timeout(2500)
        last = await page.evaluate("""() => {
          const h = window.ZY.Agent.history.filter((m) => m.role === 'user');
          const m = h[h.length - 1];
          return { isArray: Array.isArray(m && m.content), parts: Array.isArray(m && m.content) ? m.content.map((p) => p.type) : null };
        }""")
        NOTES.append(f"model_message={json.dumps(last, ensure_ascii=False)}")
        if not last.get("isArray"): FAILS.append("multimodal user message was not built as content parts")
        elif "image_url" not in (last.get("parts") or []): FAILS.append("image_url part missing from model message")

        # ── 6. orb animation styles all render and are switchable ──
        styles = await page.evaluate("window.ZY.ORB.styles.map((s) => s.id)")
        NOTES.append(f"orb_styles={styles}")
        if len(styles) < 5: FAILS.append(f"expected >=5 orb styles, got {len(styles)}")
        for s in styles:
            await page.evaluate("(s) => window.ZY.ORB.setStyle(s)", s)
            cur = await page.evaluate("window.ZY.ORB.style")
            if cur != s: FAILS.append(f"orb style {s} did not apply")

        NOTES.append(f"console_errors={errs[:3]}")
        if errs: FAILS.append(f"console errors: {errs[:3]}")
        await b.close()


asyncio.run(run())
print("── NOTES ──")
for n in NOTES: print(" •", n)
if FAILS:
    print("── FAIL ──")
    for f in FAILS: print(" •", f)
    sys.exit(1)
print("VOICE + ATTACHMENT CHECKS PASSED")
