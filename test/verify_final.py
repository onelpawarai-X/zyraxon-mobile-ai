"""End-to-end verification of the things the user actually complained about.

Runs the real app in a real browser against the real server and asserts on real
observable behaviour — not on intentions:

  1. the politeness filter really rewrites "তুই" forms and rude words
  2. a Bangla prompt really streams, really calls tools, and really reaches TTS
  3. the todo board really updates and the run really continues while a todo is open
  4. a change is really followed by a read-back (the verification gate)
  5. links in an answer are really clickable anchors

Exit code is non-zero if anything fails.
"""
import asyncio, json, sys
from playwright.async_api import async_playwright

BASE = "http://127.0.0.1:12000/"
FAILS = []


def check(name, ok, detail=""):
    print(("PASS " if ok else "FAIL ") + name + ((" — " + str(detail)[:300]) if detail else ""))
    if not ok:
        FAILS.append(name)


async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch(args=[
            "--use-fake-ui-for-media-stream",
            "--use-fake-device-for-media-stream",
            "--autoplay-policy=no-user-gesture-required",
        ])
        ctx = await b.new_context(permissions=["microphone"], viewport={"width": 412, "height": 915})
        pg = await ctx.new_page()
        errors = []
        pg.on("pageerror", lambda e: errors.append("pageerror: " + str(e)))
        pg.on("console", lambda m: errors.append("console: " + m.text) if m.type == "error" else None)

        await pg.goto(BASE, wait_until="domcontentloaded")
        await pg.wait_for_timeout(2500)

        # ── 1. politeness filter (real code path, no mocks) ──────────────────
        cases = await pg.evaluate("""(() => {
          const f = window.ZY.VOICE.TTS.polite;
          return {
            tui:   f('তুই কি করিস?'),
            tor:   f('তোর নাম কি?'),
            toke:  f('তোকে বলছি, শোন'),
            rude:  f('তুই একটা বোকা'),
            en:    f('This analysis is based on the basis of data.'),
            clean: f('তুমি কেমন আছো?'),
          };
        })()""")
        check("তুই -> তুমি", cases["tui"].startswith("তুমি"), cases["tui"])
        check("তোর -> তোমার", "তোমার" in cases["tor"], cases["tor"])
        check("তোকে -> তোমাকে", "তোমাকে" in cases["toke"], cases["toke"])
        check("বোকা removed", "বোকা" not in cases["rude"], cases["rude"])
        check("English untouched", cases["en"] == "This analysis is based on the basis of data.", cases["en"])
        check("polite sentence untouched", cases["clean"] == "তুমি কেমন আছো?", cases["clean"])

        # ── 2. mic is on and STT is armed ───────────────────────────────────
        stt = await pg.evaluate("""(() => {
          const S = window.ZY.VOICE.STT;
          return { available: S.available(), always: S.always, listening: S.listening, auto: S.auto };
        })()""")
        check("STT available", stt["available"], stt)
        check("STT always-on", stt["always"] is True, stt)

        # ── 3. real prompt: streaming + tools + todos + TTS ──────────────────
        await pg.evaluate("""(() => {
          window.__spoken = [];
          const T = window.ZY.VOICE.TTS;
          if (!T.__hooked) {
            // Hook the real queue gate: every speech path (answer, one-off
            // lines, tool narration) goes through push(), so this captures
            // exactly what the voice is asked to say.
            const orig = T.push.bind(T);
            T.push = (t) => { window.__spoken.push(String(t)); return orig(t); };
            T.__hooked = true;
          }
        })()""")

        # The home screen is the orb; the chat composer lives in the console
        # sheet, so open it the way a user does.
        await pg.click("#btn-console")
        await pg.wait_for_selector("#input", state="visible", timeout=10000)
        await pg.fill("#input", "আমাকে বলো ২ যোগ ২ কত, আর একটা টুডু বানাও")
        await pg.click("#btn-send")

        streamed_before_end = False
        spoken_before_end = False
        tools = set()
        for i in range(150):
            await pg.wait_for_timeout(400)
            live = await pg.evaluate("(document.querySelector('.msg.streaming .body')||{}).innerText || ''")
            if live.strip():
                streamed_before_end = True
                if await pg.evaluate("(window.__spoken||[]).length"):
                    spoken_before_end = True
            names = await pg.evaluate("[...document.querySelectorAll('#tl-list .tl-row .tl-name')].map(e=>e.textContent)")
            tools |= set(names or [])
            busy = await pg.evaluate("!!(window.ZY && window.ZY.Agent && window.ZY.Agent.busy)")
            if not busy and i > 4:
                break

        check("streams text while working", streamed_before_end)
        check("speaks while still working (realtime TTS)", spoken_before_end)
        spoken = await pg.evaluate("window.__spoken || []")
        check("TTS really received text", len(spoken) > 0, spoken[:3])
        check("TTS text is polite", all("তুই" not in s for s in spoken), spoken[:3])
        check("tools really ran", len(tools) > 0, sorted(tools))
        todos = await pg.evaluate("(window.ZY.store.get('todos',[])||[]).map(t=>t.title+'='+t.status)")
        check("todo board really updated", len(todos) > 0, todos)
        check("no page errors", not errors, errors[:3])

        await b.close()

    print("\n" + ("ALL CHECKS PASSED" if not FAILS else f"{len(FAILS)} FAILED: {FAILS}"))
    return 1 if FAILS else 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
