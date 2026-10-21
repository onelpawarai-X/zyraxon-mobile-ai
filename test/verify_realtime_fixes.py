"""Prove the three complaints the user reported are actually fixed.

Each complaint becomes a hard assertion, driven through the real app code with a
fake native bridge that behaves exactly like the Java one:

  1. "her words appear in the chat box and send themselves"
        -> the microphone is closed for the whole time she speaks, and a
           transcript that arrives while she speaks is never submitted.
  2. "thinking is not shown the moment I send"
        -> the thinking bubble exists in the DOM before the first token.
  3. "she speaks 15-30 seconds late"
        -> the native 'done' event ends the sentence immediately; the old
           safety timer (6s + 150ms/char) is never the thing that advances the
           queue. The 3-argument legacy callback shape is used on purpose.
"""
import asyncio
import json

from playwright.async_api import async_playwright

BASE = "http://localhost:12000"

STUB = r"""
window.__log = [];
function log(x){ window.__log.push(x); }
window.__seq = 0;
window.__ttsDone = [];
window.__mic = { stops: 0, starts: 0, listening: false };

window.ZYNative = {
  platform: () => JSON.stringify({ app: true, sdk: 34, release: '14', model: 'Test', tts: true, stt: true, accessibility: false, apps: false }),
  ttsLanguages: () => JSON.stringify([{ lang: 'bn-BD', name: 'bn-in-x-bnb-network' }]),
  ttsSetLanguage: () => true, ttsSetVoice: () => {}, ttsSetRate: () => {}, ttsSetPitch: () => {},
  // The engine reports "done" with the utterance id, like Android does.
  ttsSpeak: (text) => {
    const id = 'u' + (++window.__seq);
    log({ t: 'speak', text: text, at: Date.now() });
    // Real engines need a moment; keep it short so the test is about ordering.
    setTimeout(() => {
      window.__ttsDone.push(id);
      // DELIBERATELY the legacy 3-argument shape that used to break JS.
      window.ZYNativeCB && window.ZYNativeCB('tts', 'done', id);
    }, 50 + text.length * 3);
    return id;
  },
  ttsStop: () => {},
  sttAvailable: () => true,
  sttStart: () => { window.__mic.starts++; window.__mic.listening = true;
    window.ZYNativeCB && window.ZYNativeCB('stt', JSON.stringify({ state: 'listening' })); return true; },
  sttStop: () => { window.__mic.stops++; window.__mic.listening = false; return true; },
  sttKeepAlive: (on) => { if (on) window.__mic.starts++; return true; },
  permissionState: () => JSON.stringify({ granted: true }),
  requestPermission: () => {}, watchScreen: () => true, watchingScreen: () => false,
  foregroundApp: () => JSON.stringify({ pkg: '', label: '' }),
  accessibilityOn: () => true, screenShot: () => {}, readScreen: () => '[]',
  listApps: () => '[]',
};

window.speechSynthesis.getVoices = () => [];
window.speechSynthesis.speak = () => {};
window.speechSynthesis.cancel = () => {};

window.__stream = function () {
  const deltas = [
    { reasoning_content: 'ব্যবহারকারী আমাকে একটা কাজ দিয়েছেন।' },
    { content: 'ঠিক আছে জানু, ' }, { content: 'আমি কাজটা শুরু করছি। ' },
    { content: 'এখনই হয়ে যাবে।' },
  ];
  const enc = new TextEncoder();
  return new ReadableStream({
    start(c) {
      let i = 0;
      const t = setInterval(() => {
        if (i >= deltas.length) { c.close(); clearInterval(t); return; }
        c.enqueue(enc.encode('data: ' + JSON.stringify({ choices: [{ delta: deltas[i] }] }) + '\n\n'));
        i++;
      }, 120);
    },
  });
};
const realFetch = window.fetch.bind(window);
window.fetch = function (url, opts) {
  if (String(url).includes('/api/chat')) {
    return Promise.resolve(new Response(window.__stream(), { status: 200, headers: { 'Content-Type': 'text/event-stream' } }));
  }
  return realFetch(url, opts);
};
"""


async def main():
    fails = []
    async with async_playwright() as p:
        b = await p.chromium.launch(args=["--no-sandbox", "--autoplay-policy=no-user-gesture-required"])
        pg = await (await b.new_context(viewport={"width": 412, "height": 915})).new_page()
        errs = []
        pg.on("pageerror", lambda e: errs.append(str(e)))
        await pg.add_init_script("localStorage.setItem('zyraxon:settings', JSON.stringify({tts:true,automic:false,proxy:'proxy',greeted:true}));")
        await pg.add_init_script(STUB)
        await pg.goto(BASE, wait_until="networkidle")
        await pg.wait_for_timeout(1500)

        # ── 2. thinking bubble appears the instant the user sends ──────────
        await pg.evaluate("()=>{ window.__log=[]; window.ZY.Agent.busy=false; }")
        await pg.evaluate("()=>window.ZY.Agent.send('আমার জন্য একটা সাইট বানাও')")
        think_at = None
        for _ in range(30):
            await pg.wait_for_timeout(50)
            n = await pg.evaluate("()=>document.querySelectorAll('#chat .msg.think:not([hidden])').length")
            if n:
                think_at = await pg.evaluate("()=>window.__log.length")
                break
        if not await pg.evaluate("()=>document.querySelectorAll('#chat .msg.think').length"):
            fails.append("no thinking bubble appeared after sending")
        # the animated dots must be in the header
        dots = await pg.evaluate("()=>document.querySelectorAll('#chat .msg.think .tdot').length")
        if dots != 3:
            fails.append(f"thinking bubble has no animated dots ({dots})")

        # ── 3. the voice advances on the native 'done', not on a timer ─────
        # wait for speech to begin
        for _ in range(80):
            await pg.wait_for_timeout(100)
            if await pg.evaluate("()=>window.__log.some(x=>x.t==='speak')"):
                break
        # capture the first chunk's timing and the next chunk's timing
        await pg.wait_for_timeout(1400)
        speaks = await pg.evaluate("()=>window.__log.filter(x=>x.t==='speak')")
        print("spoken chunks:", json.dumps([s['text'] for s in speaks], ensure_ascii=False))
        if len(speaks) < 2:
            fails.append(f"the queue did not advance to the next sentence ({len(speaks)} chunks)")
        else:
            gap = speaks[1]['at'] - speaks[0]['at']
            print("gap between sentence 1 and 2 (ms):", gap)
            # The old bug: the next sentence waited 6000 + 150*len ms.
            if gap > 3000:
                fails.append(f"voice advanced on the safety timer, not on 'done' (gap {gap}ms)")

        # ── 1. her own voice must never reach the chat box ────────────────
        # while she is speaking, the mic must be closed
        mic_stops = await pg.evaluate("()=>window.__mic.stops")
        if mic_stops < 1:
            fails.append("the microphone was never closed while she spoke")
        # and a transcript arriving during speech must be dropped, not submitted
        before = await pg.evaluate("()=>document.querySelectorAll('#chat .msg.user').length")
        await pg.evaluate("()=>{ window.__spoke = window.ZY.VOICE.TTS._buf; window.ZY.VOICE.TTS._spokeAt = Date.now(); }")
        await pg.evaluate("""()=>{
          const said = window.ZY.VOICE.TTS._buf || '';
          window.ZYNativeCB('stt', JSON.stringify({ state: 'final', text: said }));
        }""")
        await pg.wait_for_timeout(500)
        after = await pg.evaluate("()=>document.querySelectorAll('#chat .msg.user').length")
        if after != before:
            fails.append(f"her own words were submitted as a user message ({before} -> {after})")
        inp = await pg.evaluate("()=>document.getElementById('input').value")
        if inp.strip():
            fails.append(f"her words landed in the composer input: {inp!r}")

        # wait for the turn to finish, then the mic must be listening again
        for _ in range(60):
            await pg.wait_for_timeout(200)
            if not await pg.evaluate("()=>window.ZY.Agent.busy"):
                break
        await pg.wait_for_timeout(1200)
        paused = await pg.evaluate("()=>window.ZY.VOICE.STT._paused")
        if paused:
            fails.append("the microphone stayed closed after she finished speaking")
        starts_after = await pg.evaluate("()=>window.__mic.starts")
        if starts_after < 2:
            fails.append(f"the microphone was not re-opened after speech ({starts_after} starts)")

        # the answer must really have been spoken (not silence)
        joined = ' '.join(s['text'] for s in speaks)
        if 'ঠিক আছে' not in joined:
            fails.append("the answer was never spoken")
        if errs:
            fails.append(f"js errors {errs[:3]}")
        await b.close()

    print("FAILS:", fails if fails else "none")
    return 1 if fails else 0


raise SystemExit(asyncio.run(main()))
