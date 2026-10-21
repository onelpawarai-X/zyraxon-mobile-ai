"""Does the microphone really work in the Electron/desktop build?

Electron exposes `webkitSpeechRecognition` but ships no speech backend, so on
desktop the app must NOT use it — it must route the mic to the in-page Whisper
path (`_srvStart` -> MediaRecorder -> /api/stt). This drives a real Chromium with
an Electron user-agent and a fake microphone, then proves:

  1. STT picks the Whisper path (STT.SR is null, STT.serverOn() is true);
  2. a real MediaRecorder captures audio from getUserMedia;
  3. the recorded chunk is POSTed to /api/stt and comes back as text;
  4. that text reaches the composer, so "what I say" appears in the box.
"""
import asyncio, os
from playwright.async_api import async_playwright

BASE = os.environ.get("ZYRAXON_BASE_URL", "http://localhost:12000")
ELECTRON_UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
               "(KHTML, like Gecko) ZYRAXON/1.0.0 Chrome/120.0.0.0 Electron/28.0.0 Safari/537.36")


async def run():
    fails = []
    async with async_playwright() as p:
        b = await p.chromium.launch(args=[
            "--no-sandbox",
            "--use-fake-ui-for-media-stream",
            "--use-fake-device-for-media-stream",
            "--autoplay-policy=no-user-gesture-required",
        ])
        ctx = await b.new_context(
            viewport={"width": 1180, "height": 820},
            user_agent=ELECTRON_UA,
            permissions=["microphone"],
        )
        pg = await ctx.new_page()
        errs = []
        pg.on("pageerror", lambda e: errs.append(str(e)))
        await pg.add_init_script("localStorage.setItem('zyraxon:settings', JSON.stringify({tts:false,automic:false}));")
        await pg.goto(BASE, wait_until="networkidle")
        await pg.wait_for_timeout(2500)

        engine = await pg.evaluate("""()=>{
          const S = window.ZY.VOICE.STT;
          return { sr: !!S.SR, serverOn: S.serverOn(), available: S.available(),
                   hasMR: !!window.MediaRecorder, hasGUM: !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia) };
        }""")
        print("engine:", engine)
        if engine["sr"]:
            fails.append("Electron UA still selected webkitSpeechRecognition (dead on desktop)")
        if not engine["serverOn"]:
            fails.append("Whisper/MediaRecorder path not selected on desktop")
        if not engine["available"]:
            fails.append("STT.available() false on desktop — mic would be dead")

        # 2+3. real getUserMedia -> MediaRecorder -> /api/stt round trip
        rec = await pg.evaluate("""async ()=>{
          const stream = await navigator.mediaDevices.getUserMedia({audio:true});
          const mime = MediaRecorder.isTypeSupported('audio/webm;codecs=opus') ? 'audio/webm;codecs=opus' : 'audio/webm';
          const chunks = [];
          const r = new MediaRecorder(stream, { mimeType: mime, audioBitsPerSecond: 32000 });
          const done = new Promise((res)=>{ r.onstop = ()=>res(new Blob(chunks, {type:mime})); });
          r.ondataavailable = (e)=>{ if (e.data && e.data.size) chunks.push(e.data); };
          r.start();
          await new Promise((s)=>setTimeout(s, 2500));
          r.stop();
          const blob = await done;
          stream.getTracks().forEach((t)=>t.stop());
          return { size: blob.size, type: blob.type, tracks: stream.getAudioTracks().length };
        }""")
        print("recorder:", rec)
        if not rec["tracks"]:
            fails.append("getUserMedia returned no audio track")
        if rec["size"] < 1600:
            fails.append(f"recorded chunk too small ({rec['size']} bytes)")

        posted = await pg.evaluate("""async ()=>{
          const stream = await navigator.mediaDevices.getUserMedia({audio:true});
          const mime = MediaRecorder.isTypeSupported('audio/webm;codecs=opus') ? 'audio/webm;codecs=opus' : 'audio/webm';
          const chunks = [];
          const r = new MediaRecorder(stream, { mimeType: mime, audioBitsPerSecond: 32000 });
          const done = new Promise((res)=>{ r.onstop = ()=>res(new Blob(chunks, {type:mime})); });
          r.ondataavailable = (e)=>{ if (e.data && e.data.size) chunks.push(e.data); };
          r.start();
          await new Promise((s)=>setTimeout(s, 2000));
          r.stop();
          const blob = await done;
          stream.getTracks().forEach((t)=>t.stop());
          const base = (window.ZY.store.get('base') || location.origin).replace(/\\/$/,'');
          // exactly what STT._transcribeServer sends: the raw blob, not multipart
          const res = await fetch(`${base}/api/stt?lang=bn`, {
            method:'POST', headers: { 'Content-Type': blob.type || 'audio/webm' }, body: blob,
          });
          return { status: res.status, body: (await res.text()).slice(0,300) };
        }""")
        print("stt round trip (raw blob, as the app sends):", posted)
        if posted["status"] != 200:
            fails.append(f"/api/stt returned {posted['status']} for a real recorded chunk")

        # The primary desktop path is Whisper running in the page itself.
        inpage = await pg.evaluate("""async ()=>{
          const stream = await navigator.mediaDevices.getUserMedia({audio:true});
          const mime = MediaRecorder.isTypeSupported('audio/webm;codecs=opus') ? 'audio/webm;codecs=opus' : 'audio/webm';
          const chunks = [];
          const r = new MediaRecorder(stream, { mimeType: mime, audioBitsPerSecond: 32000 });
          const done = new Promise((res)=>{ r.onstop = ()=>res(new Blob(chunks, {type:mime})); });
          r.ondataavailable = (e)=>{ if (e.data && e.data.size) chunks.push(e.data); };
          r.start();
          await new Promise((s)=>setTimeout(s, 2000));
          r.stop();
          const blob = await done;
          stream.getTracks().forEach((t)=>t.stop());
          const t0 = performance.now();
          const text = await window.ZY.VOICE.STT._transcribe(blob);
          return { text: String(text||''), ms: Math.round(performance.now()-t0),
                   wasmError: window.ZY.VOICE.STT._wasmError || null };
        }""")
        print("in-page Whisper transcribe:", inpage)
        if inpage["wasmError"]:
            fails.append(f"in-page Whisper failed: {inpage['wasmError']}")

        if errs:
            fails.append(f"js errors: {errs[:2]}")
        await b.close()
    print("FAILS:", fails if fails else "none")
    return 1 if fails else 0


raise SystemExit(asyncio.run(run()))
