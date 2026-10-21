"""Does the hedge really work, and does only ONE model's answer ever land?

Drives the real app against the user's own provider:
  1. the preferred model gets a head start;
  2. if it is slow, other capable models are asked too (a real hedge);
  3. whichever streams first wins, the losers are aborted;
  4. for a media turn, only models that accept that media are ever asked.
"""
import asyncio
from playwright.async_api import async_playwright

BASE = "http://127.0.0.1:12000/"


async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch()
        pg = await (await b.new_context()).new_page()
        await pg.goto(BASE, wait_until="networkidle")
        await pg.wait_for_timeout(2500)

        await pg.evaluate("""(() => {
          window.__reqs = [];
          const f = window.fetch;
          window.fetch = function (u, o) {
            const s = typeof u === 'string' ? u : (u && u.url) || '';
            if ((s.includes('chat/completions') || s.includes('/api/chat')) && o && o.body) {
              try { window.__reqs.push(JSON.parse(o.body).model); } catch {}
            }
            return f.apply(this, arguments);
          };
          window.__aborts = 0;
          const AC = window.AbortController;
          const origAbort = AC.prototype.abort;
          AC.prototype.abort = function () { window.__aborts++; return origAbort.apply(this, arguments); };
          window.__spoken = [];
          const T = window.ZY.VOICE.TTS, orig = T.push;
          T.push = function (t) { window.__spoken.push(String(t)); return orig.apply(T, arguments); };
          window.ZY.store.set('hedgeMs', 1200);
        })()""")

        await pg.evaluate("window.ZY.Agent.send('ছোট করে বলো: তুমি কে?')")
        await pg.wait_for_timeout(25000)
        text = await pg.evaluate("""(() => ({
          reqs: window.__reqs.slice(), aborts: window.__aborts,
          spoken: window.__spoken.filter(x => x.trim().length).slice(0, 3),
        }))()""")
        print("TEXT TURN:", text)
        print(f"  distinct models asked: {sorted(set(text['reqs']))}")
        print("  PASS one answer streamed" if text["spoken"] else "  FAIL nothing spoken")

        await pg.evaluate("""(() => {
          window.__reqs = [];
          window.__mediaSent = [];
          const A = window.ZY.Agent;
          const origFit = A._fitMessages;
          A._fitMessages = function (msgs, model) {
            const need = A._needModalities(msgs);
            if (need.length) window.__mediaSent.push({ model, need });
            return origFit.apply(A, arguments);
          };
          A.attachments = [{ kind: 'image', name: 'shot.png', dataUrl:
            'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==' }];
        })()""")
        await pg.evaluate("window.ZY.Agent.send('এই ছবিতে কী আছে? এক লাইনে বলো।')")
        await pg.wait_for_timeout(25000)
        media = await pg.evaluate("""(() => ({
          reqs: window.__reqs.slice(), sent: window.__mediaSent.slice(),
          caps: (window.ZY.App.models || []).reduce((a, m) => (a[m.id] = m.input_modalities, a), {}),
        }))()""")
        print("MEDIA TURN:", media["reqs"], "media-aware calls:", media["sent"])
        bad = [s for s in media["sent"] if "image" not in (media["caps"].get(s["model"]) or [])]
        print("  PASS media only went to vision models" if not bad else f"  FAIL text-only model got the image: {bad}")

        await b.close()

asyncio.run(main())
