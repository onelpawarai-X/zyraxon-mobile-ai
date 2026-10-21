"""Verify each fix against the real running app (not mocks).

  1. TTS never reads URLs, code, paths or tool names aloud
  2. Speech starts before the answer ends (real streaming, not at the end)
  3. The microphone is re-armed while/after the assistant speaks
  4. The speech box really fills with what is being said
  5. The background video does not swap back to an older clip
  6. All models really take turns (sequential) by default
"""
import asyncio, sys
from playwright.async_api import async_playwright

BASE = "http://127.0.0.1:12000/"
FAILS = []


def check(name, ok, detail=""):
    print(("PASS " if ok else "FAIL ") + name + ((" — " + str(detail)[:300]) if detail else ""))
    if not ok:
        FAILS.append(name)


async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch(args=["--use-fake-ui-for-media-stream", "--use-fake-device-for-media-stream"])
        ctx = await b.new_context(permissions=["microphone"], viewport={"width": 412, "height": 915})
        pg = await ctx.new_page()
        errors = []
        pg.on("pageerror", lambda e: errors.append(str(e)))
        await pg.goto(BASE, wait_until="networkidle")
        await pg.wait_for_timeout(2500)

        # ── 1. what TTS would speak: no URL / code / path / tool names ──────
        spoken = await pg.evaluate("""(() => {
          const T = window.ZY.VOICE.TTS;
          const samples = [
            'তোমার সাইট এখানে https://work-1-xjvfsfgnuxzdemie.prod-runtime.all-hands.dev/sites/x/ তৈরি হয়ে গেছে।',
            'ফাইল লিখলাম /workspace/project/index.html এ।',
            'কোড: `const a = 1;` এবং ```js\\nlet x=2;\\n``` লেখা শেষ।',
            'টুল publish_website চলল।'
          ];
          const out = [];
          for (const s of samples) {
            T._queue = []; T._spokenUpto = 0; T.live = '';
            T.feed(s); T.flush();
            out.push(T._queue.join(' '));
          }
          return out;
        })()""")
        joined = ' '.join(spoken)
        check("no URL is spoken", "http" not in joined and "all-hands.dev" not in joined, spoken)
        check("no path is spoken", "/workspace" not in joined and "index.html" not in joined, spoken)
        check("no code is spoken", "const a" not in joined and "let x" not in joined and "```" not in joined, spoken)
        check("no tool name is spoken", "publish_website" not in joined, spoken)
        check("the real words are still spoken", "তৈরি" in joined or "লেখা" in joined, spoken)

        # ── 2. speech starts mid-answer, not only at the end ───────────────
        timing = await pg.evaluate("""(() => {
          const T = window.ZY.VOICE.TTS;
          T._queue = []; T._spokenUpto = 0; T.live = '';
          const first = 'এটা প্রথম বাক্য যা এখনই বলা উচিত।';
          T.feed(first);
          const afterFirst = T._queue.length;
          T.feed(first + 'ক' + 'খ'.repeat(160));
          const afterRun = T._queue.length;
          return { afterFirst, afterRun };
        })()""")
        check("speech begins on the first complete sentence", timing["afterFirst"] >= 1, timing)
        check("speech also starts inside a long unpunctuated run", timing["afterRun"] > timing["afterFirst"], timing)

        # ── 3. the microphone is re-armed (watchdog present and working) ────
        wd = await pg.evaluate("""(async () => {
          const S = window.ZY.VOICE.STT;
          const T = window.ZY.VOICE.TTS;
          // Section 2 left text queued, and a non-empty queue correctly counts
          // as "she is speaking" — the gate would keep the mic shut. Reset to a
          // genuinely idle state, which is what this check is about.
          T.stop(); T._queue = []; T._speaking = false; T.live = ''; T._spokenUpto = 0;
          S._paused = false;
          const calls = [];
          const orig = S.keepAlive;
          S.keepAlive = function(){ calls.push(Date.now()); return orig.apply(S, arguments); };
          S.always = true; S._manualStop = false; S.listening = false;
          S.start();
          await new Promise(r => setTimeout(r, 3000));
          S.keepAlive = orig;
          return calls.length;
        })()""")
        check("mic is re-armed automatically while idle", wd >= 1, f"keepAlive calls={wd}")

        # ── 4. the speech box really fills ─────────────────────────────────
        box = await pg.evaluate("""(() => {
          window.ZY.ui.setSpeak('এটা এখন বলা হচ্ছে', true);
          const el = document.getElementById('speak-box');
          const txt = document.getElementById('speak-text');
          return { hidden: el.hidden, text: txt.textContent };
        })()""")
        check("speech box shows the spoken line", (not box["hidden"]) and box["text"].strip() != '', box)
        cleared = await pg.evaluate("""(() => {
          window.ZY.ui.setSpeak('', false);
          return document.getElementById('speak-box').hidden;
        })()""")
        check("speech box clears when the voice stops", cleared is True, cleared)

        # ── 5. background video does not swap back ─────────────────────────
        bg = await pg.evaluate("""(async () => {
          const B = window.ZY.BG;
          B.set('aurora');
          const src1 = document.getElementById('bg-video').src;
          await new Promise(r => setTimeout(r, 500));
          B.set('aurora');
          const src2 = document.getElementById('bg-video').src;
          return { same: src1 === src2 && /aurora/.test(src2), src1, src2 };
        })()""")
        check("re-selecting the same scene does not reload the clip", bg["same"], bg)

        # ── 6. one model handles everything; media goes to the chosen model ─
        seq = await pg.evaluate("""(() => {
          const A = window.ZY.Agent;
          const rounds = [1,2,3,4,5,6].map(r => A._modelForRound(r));
          return { on: A.sequential, rounds, first: rounds[0], chosen: A.model };
        })()""")
        check("multi-model relay is OFF by default (one model does the job)", seq["on"] is False, seq)
        check("every round runs on the chosen model", len(set(seq["rounds"])) == 1 and seq["rounds"][0] == seq["chosen"], seq)

        # A text-only model must never be told an image is "nothing".
        media = await pg.evaluate("""(() => {
          const A = window.ZY.Agent;
          const msgs = [{ role: 'user', content: [
            { type: 'text', text: 'দেখো' },
            { type: 'image_url', image_url: { url: 'data:image/png;base64,AAAA' } },
            { type: 'video_url', video_url: { url: 'data:video/mp4;base64,BBBB' } },
          ] }];
          const forText = A._fitMessages(msgs, 'zyraxon-apex');
          const forVis  = A._fitMessages(msgs, 'zyraxon-visionary');
          const kinds = (m) => m[0].content.map(p => p.type);
          return { text: kinds(forText), vis: kinds(forVis) };
        })()""")
        check("a text-only model gets no image part", "image_url" not in media["text"] and "video_url" not in media["text"], media)
        check("a vision model still gets the real image and video", "image_url" in media["vis"] and "video_url" in media["vis"], media)
        check("the watcher model is off by default", await pg.evaluate("window.ZY.Monitor.enabled") is False)

        # ── 7. earlier output is never wiped (the "আগের কোড লুট" bug) ──────
        arts = await pg.evaluate("""(() => {
          const U = window.ZY.ui;
          U.clearArtifacts();
          U.showArtifact('index.html', '<h1>one</h1>');
          U.showArtifact('app.js', 'console.log(2)');
          U.showArtifact('style.css', 'body{margin:0}');
          const cards = document.querySelectorAll('#artifact-body .art-card');
          return { n: cards.length, titles: [...cards].map(c => c.querySelector('b').textContent) };
        })()""")
        check("earlier output stays on screen", arts["n"] == 3, arts)
        check("newest output is first", arts["titles"][0] == 'style.css', arts)

        # ── 8. mid-task talk: work continues, old speech ends only when the
        #       reply to the new words actually starts ─────────────────────
        mid = await pg.evaluate("""(() => {
          const T = window.ZY.VOICE.TTS, A = window.ZY.Agent;
          T._speaking = true; T._queue = ['একটা লম্বা বাক্য যা এখন বলা হচ্ছে'];
          A.busy = true; A.pending = []; A._stopSpeechOnNextToken = false;
          A.send('আরেকটা কাজ যোগ করো');
          const stillReading = T._speaking && T._queue.length === 1;
          const queued = A.pending.length;
          const stillBusy = A.busy;
          const armed = A._stopSpeechOnNextToken === true;
          A.busy = false; A.pending = []; A._stopSpeechOnNextToken = false; T._speaking = false; T._queue = [];
          return { stillReading, queued, stillBusy, armed };
        })()""")
        check("the old sentence keeps being read", mid["stillReading"] is True, mid)
        check("the running task is not killed", mid["stillBusy"] is True, mid)
        check("the new words are kept for the next round", mid["queued"] == 1, mid)
        check("speech is armed to stop when the new reply starts", mid["armed"] is True, mid)

        check("no page errors", not errors, errors[:3])
        await b.close()

    print("\n" + ("ALL FIX CHECKS PASSED" if not FAILS else f"{len(FAILS)} FAILED: {FAILS}"))
    return 1 if FAILS else 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
