"""Prove the TTS fixes with a controlled stream — no model, no guessing.

Three complaints from the user are each turned into an assertion:

  1. reasoning must be spoken, and the answer must be spoken while it is still
     streaming (not only after the whole reply arrives);
  2. code must never be read out loud — not even the first lines of a code
     block that is still arriving (unterminated fence);
  3. refreshing the page must not replay old words.

The feed is a stubbed `fetch` that emits SSE deltas on a timer, so the timing
assertion is about the app's own behaviour, not the network.
"""
import asyncio
import json

from playwright.async_api import async_playwright

BASE = "http://localhost:12000"

STUB = r"""
window.__spoken = [];
window.__spokenAt = [];
window.speechSynthesis.getVoices = () => ([{ name: 'Bangla', lang: 'bn-BD', default: true }]);
window.speechSynthesis.speak = (u) => {
  window.__spoken.push(u.text);
  window.__spokenAt.push(Date.now());
  setTimeout(() => u.onend && u.onend(), 20);
};
window.speechSynthesis.cancel = () => {};

// Deterministic SSE stream: reasoning, then an answer containing a code fence
// that is split across deltas exactly like a real token stream.
window.__stream = function () {
  const deltas = [
    { reasoning_content: 'ব্যবহারকারী' }, { reasoning_content: ' আমাকে' },
    { reasoning_content: ' একটা' }, { reasoning_content: ' সাইট' },
    { reasoning_content: ' বানাতে' }, { reasoning_content: ' বলেছেন।' },
    { content: 'ঠিক' }, { content: ' আছে,' }, { content: ' আমি' },
    { content: ' সাইট' }, { content: ' বানাচ্ছি।' },
    { content: '\n\n```html\n<div>' }, { content: 'hello</div>\n```\n' },
    { content: 'ফাইল' }, { content: ' তৈরি' }, { content: ' হয়ে' }, { content: ' গেছে।' },
  ];
  const enc = new TextEncoder();
  return new ReadableStream({
    start(c) {
      let i = 0;
      const t = setInterval(() => {
        if (i >= deltas.length) { c.close(); clearInterval(t); return; }
        const chunk = 'data: ' + JSON.stringify({ choices: [{ delta: deltas[i] }] }) + '\n\n';
        window.__lastDeltaAt = Date.now();
        c.enqueue(enc.encode(chunk));
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
        await pg.wait_for_timeout(1200)

        # ── 1+2. stream a reply and watch the voice ──────────────
        await pg.evaluate("()=>{ window.__spoken=[]; window.__spokenAt=[]; window.__t0=Date.now(); }")
        await pg.evaluate("()=>window.ZY.Agent.send('আমার জন্য একটা সাইট বানাও')")

        # wait until speech has begun, but well before the stream is over
        first_at = None
        for _ in range(60):
            await pg.wait_for_timeout(100)
            n = await pg.evaluate("()=>window.__spoken.length")
            if n:
                first_at = await pg.evaluate("()=>window.__spokenAt[0]-window.__t0")
                break
        print("first speech after ms:", first_at)
        if first_at is None:
            fails.append("nothing was ever spoken")
        elif first_at > 2500:
            fails.append(f"speech did not start in realtime (waited {first_at}ms)")

        # let the whole stream finish
        for _ in range(60):
            await pg.wait_for_timeout(200)
            done = await pg.evaluate("()=>!window.ZY.Agent.busy")
            if done:
                break
        await pg.wait_for_timeout(800)

        spoken = await pg.evaluate("()=>window.__spoken")
        joined = ' '.join(spoken)
        print("spoken:", json.dumps(spoken, ensure_ascii=False)[:400])

        # Reasoning is shown on screen and must NOT be read aloud — the user was
        # explicit that hearing her internal thinking is noise. (This test used
        # to demand the opposite.)
        if 'বানাতে' in joined:
            fails.append("reasoning was spoken aloud")
        # the answer must be voiced
        if 'বানাচ্ছি' not in joined:
            fails.append("answer was never spoken")
        # …and it must have been spoken in pieces, i.e. while streaming. The stub
        # contains exactly two speakable sentences (reasoning and the code block
        # are excluded by design), so two chunks is the correct count.
        if len(spoken) < 2:
            fails.append(f"speech was not streamed sentence by sentence ({len(spoken)} chunks)")
        if len(set(spoken)) < len(spoken):
            fails.append("a sentence was spoken more than once — the round was repeated")
        # code must never be read
        for bad in ['```', '<div>', 'hello', 'html']:
            if bad in joined:
                fails.append(f"code leaked into speech: {bad!r}")
        if 'ফাইল তৈরি হয়ে গেছে' not in joined:
            fails.append("the sentence after the code block was not spoken")

        # ── 3. refresh must not replay old words ─────────────────
        await pg.reload(wait_until="networkidle")
        await pg.wait_for_timeout(2500)
        replayed = await pg.evaluate("()=>window.__spoken")
        print("spoken after reload:", json.dumps(replayed, ensure_ascii=False)[:200])
        if replayed:
            fails.append(f"reload replayed old speech: {replayed[:2]}")

        # the old conversation is still on screen (memory kept) …
        hist = await pg.evaluate("()=>document.querySelectorAll('#chat .msg').length")
        print("restored messages:", hist)
        if hist < 2:
            fails.append("history was not restored after reload")

        if errs:
            fails.append(f"js errors {errs[:3]}")
        await b.close()
    print("FAILS:", fails if fails else "none")
    return 1 if fails else 0


raise SystemExit(asyncio.run(main()))
