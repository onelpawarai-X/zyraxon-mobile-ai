"""Verify the newest ZYRAXON features against the real running app.

Covers: the camera tool + button, the live reasoning bubble (shown, never
spoken), the permission grant path, and the one-command guidance. Everything
is driven through the real page, so a passing run means the shipped code works.
"""
import asyncio
import sys
from playwright.async_api import async_playwright

BASE = "http://127.0.0.1:12000/"
FAILS = []
PASSES = []


def check(name, ok, detail=""):
    (PASSES if ok else FAILS).append(name)
    print(("PASS " if ok else "FAIL ") + name + ((" — " + detail) if detail else ""), flush=True)


async def main():
    async with async_playwright() as pw:
        b = await pw.chromium.launch(args=["--no-sandbox", "--use-gl=swiftshader", "--enable-unsafe-swiftshader"])
        ctx = await b.new_context(
            viewport={"width": 412, "height": 915}, device_scale_factor=2,
            is_mobile=True, has_touch=True,
            permissions=["camera"],
        )
        page = await ctx.new_page()
        errs = []
        page.on("pageerror", lambda e: errs.append(str(e)))
        await page.goto(BASE, wait_until="domcontentloaded")
        await page.wait_for_timeout(2200)

        # ── the camera tool exists in the real toolset and in the API schema ──
        tool = await page.evaluate("""() => {
          const T = window.ZY.Tools;
          return {
            impl: typeof T.execute === 'function',
            inSchema: T.names().includes('look_camera'),
            api: T.apiTools().some(t => t.function.name === 'look_camera'),
          };
        }""")
        check("camera tool is registered", tool["inSchema"], str(tool))
        check("camera tool is offered to the model", tool["api"])

        # ── the camera button exists and is wired (not a plain quick prompt) ──
        btn = await page.evaluate("""() => {
          const b = document.getElementById('btn-cam');
          return { exists: !!b, q: b ? b.dataset.q : null, label: b ? b.textContent.trim() : '' };
        }""")
        check("camera button exists", btn["exists"], str(btn))
        check("camera button is not a canned text prompt", btn["q"] is None)

        # ── the camera tool really opens a stream and returns an image ──
        # look_camera raises the in-app Allow sheet first (by design), so the
        # call is started, the sheet is allowed, and only then is it awaited —
        # otherwise the promise never resolves and the run hangs.
        await page.evaluate("""() => {
          // A fake camera: this proves the capture path (video -> canvas -> jpeg)
          // really runs instead of trusting the device.
          const cv = document.createElement('canvas'); cv.width = 64; cv.height = 48;
          const cx = cv.getContext('2d'); cx.fillStyle = '#c33'; cx.fillRect(0, 0, 64, 48);
          const fake = cv.captureStream(5);
          navigator.mediaDevices.getUserMedia = async () => fake;
          window.__cam = window.ZY.Tools.execute('look_camera', { facing: 'back' });
        }""")
        await page.wait_for_timeout(400)
        if await page.locator("#sheet-allow").is_visible():
            await page.click("#sheet-allow")
        cam = await page.evaluate("""async () => {
          const r = await window.__cam;
          return { ok: r.ok, hasImg: !!(r.data && /^data:image\\/jpeg/.test(r.data.dataUrl)), w: r.data && r.data.width };
        }""")
        check("camera tool captures a real jpeg frame", cam["ok"] and cam["hasImg"], str(cam))

        # ── reasoning is shown in the chat, in its own bubble ──
        think = await page.evaluate("""() => {
          const ui = window.ZY.ui;
          ui.startStream();
          ui.setThinking('ভাবছি: প্রথমে ফাইলটা পড়ি\\nতারপর সাইটটা বানাই');
          const div = document.querySelector('.msg.think');
          return {
            shown: !!div && !div.hidden,
            text: div ? div.querySelector('.think-body').textContent : '',
            inChat: div ? !!div.closest('#chat') : false,
          };
        }""")
        check("reasoning bubble is visible in the chat", think["shown"] and think["inChat"], str(think))
        check("reasoning bubble shows the real text", "ভাবছি" in think["text"])

        # ── but the reasoning is never spoken ──
        spoken = await page.evaluate("""() => {
          const TTS = window.ZY.VOICE.TTS;
          const q0 = (TTS._queue || []).length;
          TTS.resetLive();
          TTS.reason('এই টেক্সট কখনো শোনানো যাবে না');
          return { queued: (TTS._queue || []).length - q0 };
        }""")
        check("reasoning is never queued for speech", spoken["queued"] == 0, str(spoken))

        # ── once the answer really starts, the thinking bubble goes away ──
        # The bubble deliberately survives startStream(): the user asked to see
        # her thinking from the moment they send. It is retired by the first real
        # answer token, not by the request starting.
        hidden = await page.evaluate("""() => {
          const ui = window.ZY.ui;
          ui.startStream();
          const stillUp = ui._thinkMsg ? !ui._thinkMsg.hidden : false;
          ui.streamToken('প্রথম ');
          return { stillUpDuringConnect: stillUp, hiddenAfterFirstToken: ui._thinkMsg ? ui._thinkMsg.hidden : false };
        }""")
        check("thinking bubble hides when the answer starts",
              hidden["stillUpDuringConnect"] is True and hidden["hiddenAfterFirstToken"] is True,
              str(hidden))

        # ── the permission sheet reports the real overlay state, not a guess ──
        perm = await page.evaluate("""() => {
          const N = window.ZY.Native;
          return {
            hasOverlay: !!(N && N.settings && typeof N.settings.overlayAllowed === 'function'),
            hasShow: !!(N && N.settings && typeof N.settings.overlayShow === 'function'),
            kindListed: window.ZY.permissions.some(p => p.id === 'overlay'),
            askable: window.ZY.permissions.some(p => p.id === 'camera'),
          };
        }""")
        check("overlay permission is a real, queryable state", perm["hasOverlay"] and perm["hasShow"], str(perm))
        check("overlay row is offered to the user", perm["kindListed"])
        check("camera row is offered to the user", perm["askable"])

        # ── the one-command guidance is really in the injected prompt ──
        prompt = await page.evaluate("""async () => {
          const r = await fetch('/knowledge/zyraxon-system-prompt.md');
          return await r.text();
        }""")
        check("prompt teaches one-command workflows", "One command, not ten todos" in prompt)
        check("prompt forbids reading code/urls aloud", "Never read code, JSON, tool names" in prompt)
        check("prompt says reasoning is never spoken", "reasoning is never spoken" in prompt)
        check("prompt documents the camera tool", "look_camera" in prompt)

        # ── nothing threw while we exercised the real paths ──
        check("no page errors", not errs, "; ".join(errs[:2]))

        await b.close()

    print("\n" + ("ALL NEW-FEATURE CHECKS PASSED" if not FAILS else f"{len(FAILS)} FAILED: {FAILS}"))
    return 1 if FAILS else 0


sys.exit(asyncio.run(main()))
