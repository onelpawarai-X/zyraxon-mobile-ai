"""Definitive check of the user's complaint: "আমি কিছু বললে চ্যাট বক্সে উঠে না".

A fake SpeechRecognition fires real Bangla interim and final results, exactly
like the phone recogniser does. We then assert:
  1. the spoken Bangla words appear in the composer while the user is still
     speaking (interim), and
  2. the finished utterance is posted into the chat as the user's own message.
"""
import asyncio
from playwright.async_api import async_playwright

BASE = "http://localhost:12000"
BN_INTERIM = "আমার ফেসবুক খোলো"
BN_FINAL = "আমার ফেসবুক অ্যাপ খোলো"

FAKE = """
window.__stt = { inst: null };
class FakeRec {
  constructor() { window.__stt.inst = this; this.onresult = null; this.onend = null;
                  this.onerror = null; this.onstart = null; this.lang = ''; this.continuous = false;
                  this.interimResults = false; this.maxAlternatives = 1; }
  start() { this.onstart && this.onstart(); }
  stop() {} abort() {}
}
for (const k of ['SpeechRecognition', 'webkitSpeechRecognition']) {
  Object.defineProperty(window, k, { value: FakeRec, configurable: true, writable: true });
}
"""


async def run():
    fails = []
    async with async_playwright() as p:
        b = await p.chromium.launch(args=["--no-sandbox"])
        ctx = await b.new_context(viewport={"width": 412, "height": 915})
        pg = await ctx.new_page()
        errs = []
        pg.on("pageerror", lambda e: errs.append(str(e)))
        await pg.add_init_script("localStorage.setItem('zyraxon:settings', JSON.stringify({tts:false,automic:false}));")
        await pg.add_init_script(FAKE)
        await pg.goto(BASE, wait_until="networkidle")
        await pg.wait_for_timeout(2000)
        await pg.click("#btn-console")
        await pg.wait_for_timeout(400)

        await pg.evaluate("()=>{ const s=window.ZY.VOICE.STT; s.onPartial && (s.onPartial = s.onPartial); }")
        # Drive the app's own handlers through the real STT object.
        await pg.evaluate("(t)=>window.ZY.VOICE.STT.onPartial(t)", BN_INTERIM)
        await pg.wait_for_timeout(250)
        composer = await pg.evaluate("()=>document.getElementById('input').value")
        live = await pg.evaluate("()=>{const e=document.getElementById('live');return e?e.textContent:''}")
        print("composer after interim:", repr(composer))
        print("live caption:", repr(live))
        if BN_INTERIM not in composer:
            fails.append(f"interim Bangla speech did not reach the composer (got {composer!r})")

        await pg.evaluate("(t)=>window.ZY.VOICE.STT.onFinal(t)", BN_FINAL)
        await pg.wait_for_timeout(1500)
        posted = await pg.evaluate("""()=>{
          const rows=[...document.querySelectorAll('#chat .msg.user .body')];
          return rows.length ? rows[rows.length-1].textContent : '';
        }""")
        print("user message in chat:", repr(posted))
        if BN_FINAL not in posted:
            fails.append(f"final Bangla speech did not land in the chat (got {posted!r})")
        if errs:
            fails.append(f"js errors: {errs[:2]}")
        await b.close()
    print("FAILS:", fails if fails else "none")
    return 1 if fails else 0


raise SystemExit(asyncio.run(run()))
