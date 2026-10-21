"""Verify the fixes the user reported as broken:

1. the assistant's own voice must never be posted back as a user prompt (echo)
2. emoji must never reach the speech engine
3. opening the settings / chat panel keeps the background clip running and keeps
   the crystal-glass blur — an earlier build paused the video and dropped the
   blur to save frames, and the user rejected that: the moving background is the
   thing they look at while the assistant works.
"""
import asyncio
from playwright.async_api import async_playwright

BASE = "http://localhost:12000"

STUB = r"""
window.__srActive = false;
window.__sent = [];
class FakeSR {
  constructor(){ this.lang=''; }
  start(){ window.__srActive = true; this.onstart && this.onstart(); }
  stop(){ window.__srActive = false; }
  abort(){ window.__srActive = false; }
  emit(text, final){
    const res = [[{ transcript: text, confidence: .9 }]];
    res[0].isFinal = !!final;
    this.onresult && this.onresult({ resultIndex: 0, results: res });
  }
}
window.SpeechRecognition = FakeSR;
window.webkitSpeechRecognition = FakeSR;
window.speechSynthesis.getVoices = () => ([{ name: 'Bangla', lang: 'bn-BD' }]);
window.speechSynthesis.speak = (u) => { (window.__tts = window.__tts || []).push(u.text); };
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
        await pg.wait_for_timeout(1200)

        # ── 1. emoji never reach the speech engine ───────────────
        spoken = await pg.evaluate("""async ()=>{
          const T = window.ZY.VOICE.TTS;
          window.__tts = [];
          T.stop(); T.say('আমি ভালো আছি 💜 জান! ✨ কেমন আছো? 🥰');
          await new Promise(r=>setTimeout(r,800));
          return window.__tts.concat();
        }""")
        joined = " ".join(spoken)
        bad = [c for c in joined if ord(c) > 0x2190 and ord(c) not in (0x200C, 0x200D)]
        print("actually spoken:", joined[:90])
        print("emoji leaked:", bad)
        if bad:
            fails.append(f"emoji reached TTS: {bad}")
        if "ভালো আছি" not in joined:
            fails.append("the real words were stripped along with the emoji")

        # ── 2. echo filter ───────────────────────────────────────
        echo = await pg.evaluate("""()=>{
          const T = window.ZY.VOICE.TTS;
          T.stop(); T._buf = 'হ্যাঁ জান শুনতে পাচ্ছি তুমি বলো আমি শুনছি';
          return {
            exact:  T.isEcho('হ্যাঁ জান শুনতে পাচ্ছি'),
            whole:  T.isEcho('হ্যাঁ জান শুনতে পাচ্ছি তুমি বলো আমি শুনছি'),
            real:   T.isEcho('আমার জন্য একটা ওয়েবসাইট বানাও'),
            empty:  T.isEcho(''),
          };
        }""")
        print("echo filter:", echo)
        if not echo["exact"]: fails.append("echo: partial self-speech not caught")
        if not echo["whole"]: fails.append("echo: full self-speech not caught")
        if echo["real"]: fails.append("echo: real user speech wrongly dropped")
        if not echo["empty"]: fails.append("echo: empty transcript not dropped")

        # ── 3. a full spoken reply is never submitted as a prompt ─
        await pg.click("#orb-btn")
        await pg.wait_for_timeout(500)
        await pg.evaluate("()=>{ window.ZY.VOICE.TTS.stop(); window.ZY.VOICE.TTS._buf='আমি তোমার জন্য ওয়েবসাইট বানিয়ে দিচ্ছি জান'; }")
        before = await pg.evaluate("()=>window.ZY.Agent.history.filter(m=>m.role==='user').length")
        await pg.evaluate("()=>window.ZY.VOICE.STT.rec.emit('আমি তোমার জন্য ওয়েবসাইট বানিয়ে দিচ্ছি জান', true)")
        await pg.wait_for_timeout(1200)
        after = await pg.evaluate("()=>window.ZY.Agent.history.filter(m=>m.role==='user').length")
        print(f"user turns before={before} after={after}")
        if after != before:
            fails.append("the assistant's own words were submitted as a user prompt")

        # ── 4. modal opens pause the video and drop the blur ─────
        await pg.click("#btn-settings")
        await pg.wait_for_timeout(600)
        modal = await pg.evaluate("""()=>{
          const v = document.getElementById('bg-video');
          return {
            open: !document.getElementById('drawer').hidden,
            paused: v.paused,
            cls: document.documentElement.classList.contains('modal-open'),
            blur: getComputedStyle(document.querySelector('.drawer-panel')).backdropFilter,
          };
        }""")
        print("settings open:", modal)
        if not modal["open"]: fails.append("settings drawer did not open")
        # The clip must keep playing and the glass must keep its blur: that is
        # the deliberate look, and the user asked for the background to stay live.
        if modal["paused"]: fails.append("background clip stopped while the drawer was open")
        if not modal["cls"]: fails.append("modal-open class not set")
        if modal["blur"] in ("none", ""): fails.append("modal glass lost its blur")

        await pg.click("#drawer-close")
        await pg.wait_for_timeout(700)
        back = await pg.evaluate("()=>({paused:document.getElementById('bg-video').paused, cls:document.documentElement.classList.contains('modal-open')})")
        print("settings closed:", back)
        if back["paused"]: fails.append("background clip is not playing after closing settings")
        if back["cls"]: fails.append("modal-open class stuck")

        # ── 5. console does the same, and chat stays usable ──────
        await pg.click("#btn-console")
        await pg.wait_for_timeout(600)
        con = await pg.evaluate("()=>({open:!document.getElementById('console').hidden, paused:document.getElementById('bg-video').paused})")
        print("console open:", con)
        if not con["open"]: fails.append("console did not open")
        if con["paused"]: fails.append("background clip stopped while the console was open")
        await pg.fill("#input", "টেস্ট")
        await pg.wait_for_timeout(200)
        await pg.click("#console-close")
        await pg.wait_for_timeout(600)
        res = await pg.evaluate("()=>({paused:document.getElementById('bg-video').paused})")
        if res["paused"]: fails.append("background clip is not playing after closing console")

        if errs:
            fails.append(f"js errors {errs[:2]}")
        await b.close()
    print("FAILS:", fails if fails else "none")


asyncio.run(main())
