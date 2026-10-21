"""Save a real MediaRecorder chunk from Chromium's fake mic, then hand the exact
bytes to the app's own /api/stt endpoint, and also inspect the container.
"""
import asyncio, pathlib, os
from playwright.async_api import async_playwright

OUT = pathlib.Path("/tmp/rec_chunk.webm")


async def run():
    async with async_playwright() as p:
        b = await p.chromium.launch(args=[
            "--no-sandbox", "--use-fake-ui-for-media-stream",
            "--use-fake-device-for-media-stream"])
        ctx = await b.new_context(permissions=["microphone"])
        pg = await ctx.new_page()
        await pg.goto("http://localhost:12000", wait_until="domcontentloaded")
        await pg.wait_for_timeout(1500)
        data = await pg.evaluate("""async ()=>{
          const stream = await navigator.mediaDevices.getUserMedia({audio:true});
          const mime = MediaRecorder.isTypeSupported('audio/webm;codecs=opus') ? 'audio/webm;codecs=opus' : 'audio/webm';
          const chunks = [];
          const r = new MediaRecorder(stream, { mimeType: mime, audioBitsPerSecond: 32000 });
          const done = new Promise((res)=>{ r.onstop = ()=>res(new Blob(chunks,{type:mime})); });
          r.ondataavailable = (e)=>{ if (e.data && e.data.size) chunks.push(e.data); };
          r.start();
          await new Promise((s)=>setTimeout(s, 2200));
          r.stop();
          const blob = await done;
          stream.getTracks().forEach((t)=>t.stop());
          const buf = new Uint8Array(await blob.arrayBuffer());
          return { mime, size: buf.length, b64: btoa(String.fromCharCode(...buf.slice(0,64))) };
        }""")
        print("captured:", {k: data[k] for k in ("mime", "size")})
        blob_b64 = await pg.evaluate("""async ()=>{
          const stream = await navigator.mediaDevices.getUserMedia({audio:true});
          const mime = MediaRecorder.isTypeSupported('audio/webm;codecs=opus') ? 'audio/webm;codecs=opus' : 'audio/webm';
          const chunks = [];
          const r = new MediaRecorder(stream, { mimeType: mime, audioBitsPerSecond: 32000 });
          const done = new Promise((res)=>{ r.onstop = ()=>res(new Blob(chunks,{type:mime})); });
          r.ondataavailable = (e)=>{ if (e.data && e.data.size) chunks.push(e.data); };
          r.start();
          await new Promise((s)=>setTimeout(s, 2200));
          r.stop();
          const blob = await done;
          stream.getTracks().forEach((t)=>t.stop());
          const buf = new Uint8Array(await blob.arrayBuffer());
          let s=''; for (const b of buf) s += String.fromCharCode(b);
          return btoa(s);
        }""")
        import base64
        raw = base64.b64decode(blob_b64)
        OUT.write_bytes(raw)
        print("saved", OUT, len(raw), "bytes; first16:", raw[:16].hex())
        await b.close()


asyncio.run(run())
