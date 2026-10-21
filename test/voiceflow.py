"""Verify the hands-free voice loop.

Headless Chromium ships no SpeechRecognition, so we install a minimal stub for
that one browser API and then drive the app's REAL STT/TTS logic:
  tap orb -> listening -> speech -> auto-send -> thinking -> speaking
  -> reply spoken -> mic turns itself back on.
"""
import asyncio
from playwright.async_api import async_playwright

BASE = "http://localhost:12000"

STUB = r"""
window.__spoken = [];
window.__tts = [];
window.__srActive = false;
class FakeSR {
  constructor(){ this.lang=''; this.continuous=false; this.interimResults=false; }
  start(){ window.__srActive = true; this.onstart && this.onstart(); }
  stop(){ window.__srActive = false; }
  abort(){ window.__srActive = false; }
  emit(text, final){
    const res = [[{ transcript: text, confidence: 0.9 }]];
    res[0].isFinal = !!final;
    this.onresult && this.onresult({ resultIndex: 0, results: res });
  }
}
window.SpeechRecognition = FakeSR;
window.webkitSpeechRecognition = FakeSR;
// Plain voice descriptors: the app must survive engines that hand back
// non-standard voice objects (real WebViews do exactly this).
window.speechSynthesis.getVoices = () => ([
  { name: 'Bangla', lang: 'bn-BD', default: true, localService: true },
  { name: 'English', lang: 'en-US' },
]);
window.speechSynthesis.speak = (u) => { window.__tts.push(u.text); u.onend && setTimeout(u.onend, 60); };
window.speechSynthesis.cancel = () => {};
"""


async def main():
    fails = []
    async with async_playwright() as p:
        b = await p.chromium.launch(args=["--no-sandbox", "--autoplay-policy=no-user-gesture-required"])
        pg = await (await b.new_context(viewport={"width": 412, "height": 915})).new_page()
        errs = []
        pg.on("pageerror", lambda e: errs.append(str(e)))
        await pg.add_init_script("localStorage.setItem('zyraxon:settings', JSON.stringify({tts:true,automic:true,autosend:true}));")
        await pg.add_init_script(STUB)
        await pg.goto(BASE, wait_until="networkidle")
        await pg.wait_for_timeout(1500)

        # 1. one tap arms the mic
        await pg.click("#orb-btn")
        await pg.wait_for_timeout(600)
        armed = await pg.evaluate("()=>({sr:!!window.__srActive, hall:document.getElementById('voice-hall').className, badge:document.getElementById('live-badge').textContent})")
        print("after tap:", armed)
        if not armed["sr"]:
            fails.append("mic did not start on tap")
        if "listening" not in armed["hall"]:
            fails.append("listening state not reflected on the orb")

        # 2. speak -> auto-send -> thinking -> reply spoken
        await pg.evaluate("()=>{ const r = window.ZY.VOICE.STT.rec; r.emit('আজকের তারিখ বলো', true); }")
        await pg.wait_for_timeout(2500)
        thinking = await pg.evaluate("()=>document.getElementById('live-badge').textContent")
        print("badge after speech:", thinking)

        for _ in range(20):
            await pg.wait_for_timeout(2000)
            spoke = await pg.evaluate("()=>window.__tts.length")
            if spoke:
                break
        spoken = await pg.evaluate("()=>window.__tts")
        print("spoken:", spoken[:2])
        if not spoken:
            fails.append("assistant reply was never sent to TTS")

        # 3. mic comes back on by itself once speaking finishes
        await pg.wait_for_timeout(6000)
        again = await pg.evaluate("()=>({sr:!!window.__srActive, hall:document.getElementById('voice-hall').className})")
        print("after reply:", again)
        if not again["sr"]:
            fails.append("mic did not auto-restart after the reply")

        # 4. speak box mirrored the live line
        sb = await pg.evaluate("()=>({hidden:document.getElementById('speak-box').hidden, text:document.getElementById('speak-text').textContent.slice(0,60)})")
        print("speak box:", sb)

        if errs:
            fails.append(f"js errors {errs[:2]}")
        await b.close()
    print("FAILS:", fails if fails else "none")


asyncio.run(main())
