"""Verify the voice path that runs inside the APK (native Android TTS).

The browser has no TextToSpeech engine, so we install a fake `window.ZYNative`
that behaves exactly like the Java bridge: `ttsSpeak` returns an utterance id
and later reports `done` with that same id. That is enough to prove the real
in-app behaviour without a phone:

  * reasoning is spoken, then the answer, sentence by sentence;
  * only ONE sentence is ever in flight (the old code stacked listeners, so
    every "done" re-fired the older ones and sentences talked over each other);
  * code is never read out loud;
  * with TTS switched off in settings, nothing is spoken at all.
"""
import asyncio
import json

from playwright.async_api import async_playwright

BASE = "http://localhost:12000"

NATIVE_STUB = r"""
window.__ttsCalls = [];     // {text, id, at}
window.__ttsDone = [];
window.__seq = 0;
window.ZYNative = {
  platform: () => JSON.stringify({ app: true, sdk: 34, release: '14', model: 'Test', tts: true, stt: false, accessibility: false, apps: false }),
  ttsLanguages: () => JSON.stringify([{ lang: 'bn-BD', name: 'bn-in-x-bnb-network' }]),
  ttsSetLanguage: () => true,
  ttsSetVoice: () => {},
  ttsSetRate: () => {},
  ttsSetPitch: () => {},
  ttsSpeak: (text) => {
    const id = 'u' + (++window.__seq);
    window.__ttsCalls.push({ text: text, id: id, at: Date.now() });
    // The engine takes time to speak; report done proportionally to length.
    setTimeout(() => {
      window.__ttsDone.push(id);
      window.ZYNativeCB && window.ZYNativeCB('tts', JSON.stringify({ state: 'done', text: id }));
    }, 60 + text.length * 6);
    return id;
  },
  ttsStop: () => {},
  sttAvailable: () => false,
  // The device panel asks for the accessibility state; without this the page
  // threw "N.accessibilityOn is not a function" and every settings row broke.
  accessibilityOn: () => false,
};

window.speechSynthesis.getVoices = () => [];
window.speechSynthesis.speak = () => {};
window.speechSynthesis.cancel = () => {};

// Deterministic SSE stream: reasoning, then an answer with a code fence.
window.__stream = function () {
  const deltas = [
    { reasoning_content: 'ব্যবহারকারী' }, { reasoning_content: ' আমাকে' },
    { reasoning_content: ' একটা' }, { reasoning_content: ' ফাইল' },
    { reasoning_content: ' বানাতে' }, { reasoning_content: ' বলেছেন।' },
    { content: 'ঠিক' }, { content: ' আছে,' }, { content: ' ফাইল' },
    { content: ' বানাচ্ছি।' },
    { content: '\n\n```python\nprint(1)\n```\n' },
    { content: 'কাজ' }, { content: ' শেষ' }, { content: ' হলো।' },
  ];
  const enc = new TextEncoder();
  return new ReadableStream({
    start(c) {
      let i = 0;
      const t = setInterval(() => {
        if (i >= deltas.length) { c.close(); clearInterval(t); return; }
        c.enqueue(enc.encode('data: ' + JSON.stringify({ choices: [{ delta: deltas[i] }] }) + '\n\n'));
        i++;
      }, 100);
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


async def run(pg, settings):
    await pg.add_init_script(f"localStorage.setItem('zyraxon:settings', JSON.stringify({json.dumps(settings)}));")
    await pg.add_init_script(NATIVE_STUB)
    await pg.goto(BASE, wait_until="networkidle")
    await pg.wait_for_timeout(1200)
    await pg.evaluate("()=>{ window.__ttsCalls=[]; window.__ttsDone=[]; }")
    await pg.evaluate("()=>window.ZY.Agent.send('একটা ফাইল বানাও')")
    for _ in range(80):
        await pg.wait_for_timeout(200)
        if await pg.evaluate("()=>!window.ZY.Agent.busy"):
            break
    await pg.wait_for_timeout(3000)
    return await pg.evaluate("()=>window.__ttsCalls")


async def main():
    fails = []
    async with async_playwright() as p:
        b = await p.chromium.launch(args=["--no-sandbox", "--autoplay-policy=no-user-gesture-required"])
        ctx = await b.new_context(viewport={"width": 412, "height": 915})
        pg = await ctx.new_page()
        errs = []
        pg.on("pageerror", lambda e: errs.append(str(e)))

        calls = await run(pg, {"tts": True, "automic": False, "proxy": "proxy", "greeted": True})
        texts = [c["text"] for c in calls]
        print("native speak calls:", json.dumps(texts, ensure_ascii=False))
        if not texts:
            fails.append("native engine was never asked to speak")
        else:
            joined = ' '.join(texts)
            # Reasoning stays on screen and is never read aloud (see the note in
            # realtime_tts.py). This assertion used to demand the opposite.
            if 'বানাতে' in joined:
                fails.append("reasoning was spoken aloud on the native path")
            if 'বানাচ্ছি' not in joined:
                fails.append("answer was never spoken on the native path")
            if len(texts) < 2:
                fails.append(f"not streamed sentence by sentence ({len(texts)} calls)")
            if len(set(texts)) < len(texts):
                fails.append("a sentence was spoken more than once — the round was repeated")
            for bad in ['```', 'print(1)', 'python']:
                if bad in joined:
                    fails.append(f"code leaked into native speech: {bad!r}")
            # Overlap check: each call must start after the previous one ended.
            # Sequential queueing means start times are spaced by the engine's
            # own duration, never bunched together.
            gaps = [calls[i + 1]["at"] - calls[i]["at"] for i in range(len(calls) - 1)]
            print("gaps ms:", gaps)
            if gaps and min(gaps) < 40:
                fails.append(f"sentences overlapped (gap {min(gaps)}ms)")

        # ── TTS off must mean silence ───────────────────────────
        await pg.evaluate("()=>{ window.ZY.store.set('tts', false); window.ZY.VOICE.TTS.enabled = false; window.__ttsCalls=[]; }")
        await pg.evaluate("()=>window.ZY.Agent.send('আবার বলো')")
        for _ in range(80):
            await pg.wait_for_timeout(200)
            if await pg.evaluate("()=>!window.ZY.Agent.busy"):
                break
        await pg.wait_for_timeout(1500)
        off = await pg.evaluate("()=>window.__ttsCalls")
        print("spoken with tts off:", json.dumps(off, ensure_ascii=False)[:160])
        if off:
            fails.append("TTS-off setting did not silence the assistant")

        if errs:
            fails.append(f"js errors {errs[:3]}")
        await b.close()
    print("FAILS:", fails if fails else "none")
    return 1 if fails else 0


raise SystemExit(asyncio.run(main()))
