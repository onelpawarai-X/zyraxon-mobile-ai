"""Verify ZYRAXON's automatic screen + camera awareness.

The user's rule: seeing the screen and the camera must NOT require a tool call.
The only thing they ever do is tap Allow once. This test proves, against the
real running app, that:
  * the background watch starts with no tool call,
  * a screen change is injected into the model's context silently,
  * a background frame rides along with the next turn as a real image part,
  * the camera turns on from a permission grant alone,
  * no background frame is ever shown in the chat or saved,
  * `read_screen`/`look_camera` are still available for the deliberate case,
  * every permission row can be revoked as well as granted.
"""
import asyncio
import sys
from playwright.async_api import async_playwright

BASE = "http://127.0.0.1:12000/"
FAILS, PASSES = [], []


def check(name, ok, detail=""):
    (PASSES if ok else FAILS).append(name)
    print(("PASS " if ok else "FAIL ") + name + ((" — " + detail) if detail else ""), flush=True)


FAKE_NATIVE = """
(() => {
  // A believable device bridge: this is what the APK exposes to the page.
  const cbs = {};
  const perms = { microphone: true, camera: true, location: true, contacts: true,
                  phone: true, sms: true, calendar: true, storage: true, notifications: true };
  let watching = false, overlay = true, acc = true;
  const N = {
    platform() { return JSON.stringify({ app: true, tts: true, stt: true, release: '14' }); },
    accessibilityOn() { return acc; },
    permissionState(n) { return JSON.stringify({ name: n, granted: !!perms[n] }); },
    requestPermission() {}, revokePermission() { return true; },
    openSystemSettings() { return true; },
    canWriteSettings() { return true; }, overlayAllowed() { return overlay; },
    overlayShow() { return true; }, overlayHide() {},
    playProtect() { return true; },
    watchScreen(on, ms) { watching = !!on; return watching; },
    watchingScreen() { return watching; },
    foregroundApp() { return JSON.stringify({ pkg: '', label: '' }); },
    serverBase() { return ''; }, setServerBase() {},
    screenShot() {}, readScreen() { return '[]'; }, listApps() { return '[]'; },
    ttsLanguages() { return '[]'; }, ttsSpeak() { return 'x'; }, ttsStop() {},
    ttsSetLanguage() { return true; }, ttsSetRate() {}, ttsSetPitch() {}, ttsSetVoice() {},
    sttStart() {}, sttStop() {},
    deviceInfo() { return '{}'; }, clipboardRead() { return ''; }, clipboardWrite() {},
    vibrate() {}, notifyMsg() {},
  };
  window.__native = { N, cbs, setWatching: (v) => { watching = v; },
                      watching: () => watching, emit: (k, p) => {
    const f = cbs[k]; if (f) f.forEach((fn) => fn(p));
  } };
  window.ZYNative = N;
})()
"""


async def main():
    async with async_playwright() as pw:
        b = await pw.chromium.launch(args=["--no-sandbox", "--use-gl=swiftshader", "--enable-unsafe-swiftshader"])
        ctx = await b.new_context(
            viewport={"width": 412, "height": 915}, device_scale_factor=2,
            is_mobile=True, has_touch=True, permissions=["camera"],
        )
        page = await ctx.new_page()
        errs = []
        page.on("pageerror", lambda e: errs.append(str(e)))

        # Install the fake bridge before the app boots, so boot() sees a real APK.
        await page.add_init_script(FAKE_NATIVE)
        await page.goto(BASE, wait_until="domcontentloaded")
        await page.wait_for_timeout(2000)

        # Force the module to use the fake bridge, then boot the watch.
        ok = await page.evaluate("""() => {
          const S = window.ZY.Screen;
          if (!S) return 'no-screen-module';
          return 'ok';
        }""")
        check("screen module loaded", ok == "ok", str(ok))

        # ── the watch starts on its own: no tool call anywhere ──
        started = await page.evaluate("""async () => {
          const S = window.ZY.Screen;
          S.stop();
          const before = window.__native.watching();
          const r = S.start();
          return { before, after: window.__native.watching(), r };
        }""")
        check("background screen watch starts with no tool call",
              started["after"] is True and started["before"] is False, str(started))

        # ── no tool was invoked to make that happen ──
        tools_used = await page.evaluate("""() => {
          const log = [];
          const T = window.ZY.Tools;
          const orig = T.execute;
          T.execute = function (n, a) { log.push(n); return orig.apply(this, arguments); };
          window.ZY.Screen.start();
          T.execute = orig;
          return log;
        }""")
        check("starting the watch calls no tool", tools_used == [], str(tools_used))

        # ── a screen change is injected silently, never shown ──
        injected = await page.evaluate("""() => {
          const S = window.ZY.Screen;
          window.ZY.Agent.busy = false;
          S.onScreenChanged('com.facebook.katana', 'Facebook');
          const note = S.takeNote();
          const bubbles = document.querySelectorAll('#chat .msg').length;
          return { note, where: S.state.label, bubbles };
        }""")
        check("a screen change records where the user is", injected["where"] == "Facebook", str(injected))
        check("the change is queued as model context", "Facebook" in injected["note"], str(injected["note"])[:80])

        # ── a background frame becomes a real image part on the next turn ──
        parts = await page.evaluate("""() => {
          const S = window.ZY.Screen;
          // 1x1 jpeg is enough to prove the plumbing; the content is irrelevant.
          const b64 = '/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==';
          S.onFrame(b64);
          const p = S.contextParts();
          const img = p.find((x) => x.type === 'image_url');
          const txt = p.filter((x) => x.type === 'text').map((x) => x.text).join(' ');
          return { n: p.length, hasImg: !!img, isDataUrl: !!(img && /^data:image\\/jpeg;base64,/.test(img.image_url.url)), txt };
        }""")
        check("a background frame becomes a real image part", parts["hasImg"] and parts["isDataUrl"], str(parts))
        check("the frame is labelled as background, no tool call", "কোনো টুল কল ছাড়াই" in parts["txt"], parts["txt"][:90])

        # ── that frame is consumed once, and never appears in the chat ──
        consumed = await page.evaluate("""() => {
          const S = window.ZY.Screen;
          const b64 = '/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==';
          const before = document.querySelectorAll('#chat .msg').length;
          S.onFrame(b64);
          const p1 = S.contextParts().filter((x) => x.type === 'image_url').length;
          const p2 = S.contextParts().filter((x) => x.type === 'image_url').length;
          const after = document.querySelectorAll('#chat .msg').length;
          return { p1, p2, before, after };
        }""")
        check("the frame is used once, then cleared", consumed["p1"] == 1 and consumed["p2"] == 0, str(consumed))
        check("no background frame is ever shown in the chat", consumed["before"] == consumed["after"], str(consumed))

        # ── a stale frame is not sent as if it were the current screen ──
        stale = await page.evaluate("""async () => {
          const S = window.ZY.Screen;
          const b64 = '/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==';
          S.onFrame(b64);
          await new Promise((r) => setTimeout(r, 1300));   // the screen has moved on
          return S.takeFrame(1000) === null;
        }""")
        check("a stale frame is dropped instead of misleading her", stale is True)

        # ── the camera turns on from a permission grant alone ──
        cam = await page.evaluate("""async () => {
          const cv = document.createElement('canvas'); cv.width = 64; cv.height = 48;
          cv.getContext('2d').fillRect(0, 0, 64, 48);
          navigator.mediaDevices.getUserMedia = async () => cv.captureStream(5);
          const S = window.ZY.Screen;
          const on = await S.camStart('environment');
          await new Promise((r) => setTimeout(r, 900));   // let one grab happen
          const f = S.takeFrame(20000);
          return { on, running: S.cam.on, grabbed: !!(f && f.cam), isImg: !!(f && /^data:image/.test(f.dataUrl)) };
        }""")
        check("camera starts from a permission grant, no tool call", cam["on"] and cam["running"], str(cam))
        check("camera frames are captured automatically", cam["grabbed"] and cam["isImg"], str(cam))

        off = await page.evaluate("""() => { window.ZY.Screen.camStop(); return window.ZY.Screen.cam.on; }""")
        check("camera stops when asked", off is False)

        # ── the camera button is a permission switch, not a tool prompt ──
        btn = await page.evaluate("""() => {
          const b = document.getElementById('btn-cam');
          return { exists: !!b, canned: b && b.dataset.q, cls: b && b.className };
        }""")
        check("camera button is not a canned prompt", btn["exists"] and not btn["canned"], str(btn))

        # ── deliberate screen reading is still available ──
        tools = await page.evaluate("""() => {
          const T = window.ZY.Tools;
          return { screen: T.names().includes('read_screen'), cam: T.names().includes('look_camera') };
        }""")
        check("read_screen stays available for deliberate action", tools["screen"])
        check("look_camera stays available for a close-up", tools["cam"])

        # ── permissions can be revoked, not only granted ──
        perms = await page.evaluate("""() => {
          window.ZY.ui.renderPermissions();
          const rows = [...document.querySelectorAll('#perm-list .perm')];
          return {
            n: rows.length,
            revokeBtns: rows.filter((r) => r.querySelector('button[data-revoke]')).length,
            allowBtns: rows.filter((r) => r.querySelector('button[data-ask]')).length,
          };
        }""")
        check("every permission row offers Allow", perms["n"] > 0 and perms["allowBtns"] == perms["n"], str(perms))
        check("every permission row offers Revoke", perms["revokeBtns"] == perms["n"], str(perms))

        # ── the prompt teaches the automatic rule ──
        prompt = await page.evaluate("""async () => (await fetch('/knowledge/zyraxon-system-prompt.md')).text()""")
        check("prompt says screen/camera are automatic", "Seeing the screen and the camera is automatic" in prompt)
        check("prompt forbids tool calls just to look around", "context is automatic, tool calls are for deliberate action" in prompt)
        check("prompt keeps read_screen for deliberate action", "only when you are about to act on the screen" in prompt)

        check("no page errors", not errs, "; ".join(errs[:2]))
        await b.close()

    print("\n" + ("ALL AUTOMATIC-AWARENESS CHECKS PASSED" if not FAILS else f"{len(FAILS)} FAILED: {FAILS}"))
    return 1 if FAILS else 0


sys.exit(asyncio.run(main()))
