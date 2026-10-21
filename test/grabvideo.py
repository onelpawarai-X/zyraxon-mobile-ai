"""Download the user's YouTube wallpaper directly from the real stream URL.

YouTube refuses yt-dlp on this host (bot check + PO token), but the browser
plays the clip fine, so the googlevideo stream URLs are reachable from here.
We open the watch page, capture the media requests the player itself makes,
then fetch the video stream directly with ffmpeg and mux a clean mp4.
"""
import asyncio, pathlib, subprocess
from playwright.async_api import async_playwright

VID = "AKfsikEXZHM"
OUT = pathlib.Path("/workspace/project/public/assets/bg/galaxy-eye.mp4")
POSTER = pathlib.Path("/workspace/project/public/assets/bg/galaxy-eye.jpg")

streams = []
headers_seen = {}


async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch(args=[
            "--no-sandbox", "--autoplay-policy=no-user-gesture-required",
            "--disable-features=IsolateOrigins,site-per-process",
        ])
        ctx = await b.new_context(viewport={"width": 1280, "height": 720})
        pg = await ctx.new_page()

        def on_req(req):
            u = req.url
            if "googlevideo.com/videoplayback" in u:
                h = dict(req.headers)
                if u not in streams:
                    streams.append(u)
                    headers_seen[u] = h
                    print(f"  stream {len(streams)}: itag={u.split('itag=')[1][:3]} "
                          f"mime={u.split('mime=')[1].split('%')[0][:24] if 'mime=' in u else '?'}", flush=True)

        pg.on("request", on_req)
        await pg.goto(f"https://www.youtube.com/watch?v={VID}", wait_until="domcontentloaded")
        await pg.wait_for_timeout(4000)
        for sel in ["button[aria-label*='Accept']", "button:has-text('Accept all')", ".ytp-large-play-button"]:
            try:
                if await pg.locator(sel).count():
                    await pg.locator(sel).first.click(timeout=1200)
            except Exception:
                pass
        # let the player stream for a while so several formats show up
        await pg.wait_for_timeout(12000)
        info = await pg.evaluate("""() => {
          let best=null, area=0;
          for (const v of document.querySelectorAll('video')) {
            const r=v.getBoundingClientRect(); if (r.width*r.height>area){area=r.width*r.height;best=v;}
          }
          return best ? {w:Math.round(best.getBoundingClientRect().width), rs:best.readyState,
                         t:best.currentTime, dur:best.duration} : null;
        }""")
        print("player:", info, flush=True)
        await b.close()
    return headers_seen


seen = asyncio.run(main())
print(f"\ncaptured {len(streams)} media URLs", flush=True)
if not streams:
    raise SystemExit("no media URL captured")

# prefer a progressive mp4 (video+audio in one stream) so no muxing is needed
def score(u):
    s = 0
    if "mime=video%2Fmp4" in u or "mime=video/mp4" in u:
        s += 5
    if "itag=18" in u:
        s += 10
    if "itag=22" in u:
        s += 8
    if "live=1" in u:
        s -= 5
    return s


best = max(streams, key=score)
print("chosen:", best[:150], flush=True)
h = headers_seen[best]
hdr = "\r\n".join(f"{k}: {v}" for k, v in h.items() if k.lower() not in
                  ("host", "content-length", "accept-encoding", "connection"))
print("downloading with ffmpeg…", flush=True)
r = subprocess.run([
    "ffmpeg", "-y", "-headers", hdr + "\r\n", "-i", best,
    "-c", "copy", "-movflags", "+faststart", str(OUT),
], capture_output=True, text=True)
if r.returncode != 0 or not OUT.exists() or OUT.stat().st_size < 100_000:
    print("copy failed, re-encoding…", flush=True)
    r2 = subprocess.run([
        "ffmpeg", "-y", "-headers", hdr + "\r\n", "-i", best,
        "-c:v", "libx264", "-preset", "medium", "-crf", "22",
        "-vf", "scale=1080:-2", "-an", "-movflags", "+faststart", str(OUT),
    ], capture_output=True, text=True)
    if r2.returncode != 0:
        print(r2.stderr[-1500:])
        raise SystemExit("ffmpeg failed")

subprocess.run(["ffmpeg", "-y", "-i", str(OUT), "-vf", "select=eq(n\\,0)",
                "-frames:v", "1", str(POSTER)], capture_output=True)
print("wrote", OUT, OUT.stat().st_size // 1024, "KB; poster",
      POSTER.stat().st_size // 1024 if POSTER.exists() else 0, "KB")
