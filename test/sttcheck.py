"""Verify the server-side speech recogniser (the path desktop/Firefox need).

Electron ships no webkitSpeechRecognition and the provider has no audio
endpoint, so /api/stt + local Whisper is the only way the microphone works off
Android. Everything here is real: a real Bangla audio file is transcribed by a
real model, and the browser path is exercised through the real STT object.
"""
import asyncio
import json
import pathlib
import subprocess
import sys
import urllib.parse
import urllib.request

from playwright.async_api import async_playwright

BASE = "http://localhost:12000"
ROOT = pathlib.Path(__file__).resolve().parent.parent
fails = []


def check(name, ok, detail=""):
    print(("PASS " if ok else "FAIL ") + name + ((" — " + str(detail)) if detail else ""), flush=True)
    if not ok:
        fails.append(name)


def http(path, data=None, ctype=None, timeout=300):
    req = urllib.request.Request(BASE + path, data=data, headers={"Content-Type": ctype} if ctype else {})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.status, r.read()


def main_sync():
    # ── a real Bangla utterance, spoken by the app's own TTS ──
    phrase = "আমার গোপন কোড হলো বেগুনি সাতাশ"
    url = f"/api/tts?tl=bn&q={urllib.parse.quote(phrase)}"
    status, audio = http(url)
    check("tts produced audio to test with", status == 200 and len(audio) > 4000, f"{len(audio)} bytes")

    # ── the endpoint really transcribes it ──
    status, body = http("/api/stt?lang=bn", data=audio, ctype="audio/mpeg")
    j = json.loads(body)
    check("stt endpoint returns 200", status == 200, str(j)[:200])
    check("stt detected Bangla", j.get("lang") == "bn", str(j.get("lang")))
    text = (j.get("text") or "").lower()
    check("stt returned real words", len(text.split()) >= 3, text)
    # Whisper tiny/base transliterates Bangla, so match phonetically rather than
    # by script: "beguni"/"begoni" and "satash"/"shatash" are the same utterance.
    hit = sum(1 for frag in ("amar", "gop", "kod", "beg", "sat", "sh") if frag in text)
    check("transcript matches what was said", hit >= 3, f"hits={hit} text={text}")

    # ── status reports the warm worker so the UI can show readiness ──
    status, body = http("/api/stt")
    st = json.loads(body)
    check("stt status is honest", st.get("ok") is True and "ready" in st, str(st))

    # ── the browser picks the server recogniser when there is no Web Speech ──
    async def browser():
        async with async_playwright() as pw:
            b = await pw.chromium.launch(args=["--no-sandbox"])
            ctx = await b.new_context(viewport={"width": 412, "height": 915})
            pg = await ctx.new_page()
            await pg.add_init_script("""
              // Remove the engines an Electron/Firefox build would not have.
              delete window.SpeechRecognition;
              delete window.webkitSpeechRecognition;
            """)
            await pg.goto(BASE, wait_until="domcontentloaded")
            await pg.wait_for_timeout(2500)
            r = await pg.evaluate("""() => {
              const STT = window.ZY.VOICE.STT;
              return { sr: !!STT.SR, serverOn: STT.serverOn(), available: STT.available() };
            }""")
            check("no Web Speech in this profile", r["sr"] is False, str(r))
            check("server recogniser is selected", r["serverOn"] is True, str(r))
            check("microphone reports available", r["available"] is True, str(r))

            # The recording loop must really start and stop without throwing.
            started = await pg.evaluate("""async () => {
              const STT = window.ZY.VOICE.STT;
              STT.init();
              const ok = STT.start();
              await new Promise(r => setTimeout(r, 800));
              const live = !!STT._srvStream || STT._server === true;
              STT.pause();
              await new Promise(r => setTimeout(r, 300));
              return { ok, live, stopped: !STT._srvStream };
            }""")
            check("server mic loop starts", started["ok"] is True and started["live"] is True, str(started))
            check("server mic loop stops on pause", started["stopped"] is True, str(started))

            # And the real transcription path the loop uses must work in-page.
            posted = await pg.evaluate("""async () => {
              const blob = new Blob([new Uint8Array(3000)], { type: 'audio/webm' });
              const r = await fetch('/api/stt?lang=bn', { method: 'POST', headers: {'Content-Type':'audio/webm'}, body: blob });
              const j = await r.json();
              return { status: r.status, hasError: !!j.error || !!j.text };
            }""")
            check("in-page upload reaches the recogniser", posted["status"] in (200, 503) and posted["hasError"], str(posted))

            # The decisive check: the in-page Whisper model really transcribes
            # speech, with the wasm runtime served by us rather than a CDN.
            wasm = await pg.evaluate("""async (phrase) => {
              const STT = window.ZY.VOICE.STT;
              const t0 = performance.now();
              try {
                const asr = await STT._wasmModel();
                const loadMs = Math.round(performance.now() - t0);
                const buf = await (await fetch('/api/tts?tl=bn&q=' + encodeURIComponent(phrase))).arrayBuffer();
                const ctx = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 16000 });
                const decoded = await ctx.decodeAudioData(buf);
                const t1 = performance.now();
                const out = await asr(new Float32Array(decoded.getChannelData(0)), { language: 'bn', task: 'transcribe' });
                return { ok: true, loadMs, runMs: Math.round(performance.now()-t1), text: String(out.text || '') };
              } catch (e) { return { ok: false, err: String(e).slice(0, 240) }; }
            }""", "আমার গোপন কোড হলো বেগুনি সাতাশ")
            check("in-page Whisper model loads", wasm.get("ok") is True, str(wasm)[:240])
            wt = (wasm.get("text") or "").lower()
            hits = sum(1 for f in ("amar", "gop", "kod", "beg", "sat") if f in wt)
            check("in-page Whisper transcribes Bangla speech", hits >= 3, f"hits={hits} text={wt!r}")

            # …and the whole loop uses it: a blob handed to _transcribe comes back
            # as the spoken words, which is exactly what the mic path does.
            loop = await pg.evaluate("""async (phrase) => {
              const STT = window.ZY.VOICE.STT;
              const buf = await (await fetch('/api/tts?tl=bn&q=' + encodeURIComponent(phrase))).arrayBuffer();
              const blob = new Blob([buf], { type: 'audio/mpeg' });
              const text = await STT._transcribe(blob);
              return { text: String(text || '') };
            }""", "আমার গোপন কোড হলো বেগুনি সাতাশ")
            lt = (loop.get("text") or "").lower()
            hits2 = sum(1 for f in ("amar", "gop", "kod", "beg", "sat") if f in lt)
            check("STT loop turns speech into text", hits2 >= 3, f"hits={hits2} text={lt!r}")
            await b.close()

    asyncio.run(browser())

    print("\n" + ("ALL STT CHECKS PASSED" if not fails else f"{len(fails)} FAILED: {fails}"))
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main_sync())
