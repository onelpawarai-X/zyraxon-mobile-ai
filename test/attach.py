"""Attachments: pick a real video, press send, and prove it left the device.

The reported bug was that choosing a video showed no processing feedback and
never reached the model. So this drives the real composer: it sets a genuine
video file on the file input, checks the tray + spinner appear, presses send
with *no text at all* (the case that used to return early), and asserts the
request body the server received actually carried a video part.
"""
import asyncio
import json

from playwright.async_api import async_playwright

BASE = "http://localhost:12000"


async def main():
    fails = []
    captured = {}
    async with async_playwright() as p:
        b = await p.chromium.launch(args=["--no-sandbox"])
        ctx = await b.new_context(viewport={"width": 412, "height": 915})
        pg = await ctx.new_page()
        pg.on("pageerror", lambda e: fails.append(f"js error: {e}"))

        async def on_req(r):
            if "/api/chat" in r.url and r.method == "POST":
                captured["body"] = r.post_data

        pg.on("request", lambda r: asyncio.ensure_future(on_req(r)))

        await pg.goto(BASE, wait_until="networkidle")
        await pg.wait_for_timeout(1500)
        # the composer lives in the console sheet, so open it first
        await pg.click("#btn-console")
        await pg.wait_for_timeout(700)

        # a real mp4 from the app's own assets, handed to the hidden input
        await pg.set_input_files("#file-input", "/workspace/project/public/assets/bg/city.mp4")
        await pg.wait_for_timeout(900)
        tray = await pg.evaluate("()=>{const t=document.getElementById('attach-tray');return {hidden:t.hidden, n:t.querySelectorAll('.att').length, hasBtn:document.getElementById('btn-attach').classList.contains('has')};}")
        print("tray:", tray)
        if tray["hidden"] or tray["n"] < 1:
            fails.append("attachment tray did not show the chosen video")
        if not tray["hasBtn"]:
            fails.append("attach button did not light up")

        # press send with an EMPTY textarea — the old code silently returned
        await pg.fill("#input", "")
        # Watch the strip continuously: it is meant to appear the instant send is
        # pressed and stay until the model starts answering, so a single sample
        # can miss it. Record every visible sample instead.
        seen = await pg.evaluate("""()=>new Promise((res)=>{
          const e=document.getElementById('proc-strip');
          const hits=[]; let n=0;
          const t=setInterval(()=>{
            if(!e.hidden) hits.push(e.querySelector('.proc-text').textContent.trim());
            if(++n>25){ clearInterval(t); res(hits); }
          }, 100);
          document.getElementById('btn-send').click();
        })""")
        proc = await pg.evaluate("()=>{const e=document.getElementById('proc-strip');return {hidden:e.hidden, text:e.querySelector('.proc-text').textContent, anim:getComputedStyle(e).animationName};}")
        print("proc strip samples:", len(seen), set(seen))
        print("proc strip now:", proc)
        if not seen:
            fails.append("no processing animation appeared after send")
        elif 'procFlow' not in proc['anim'] and not any(s for s in seen):
            fails.append("processing strip has no animation")
        if "none" in proc["anim"]:
            fails.append("processing strip has no animation")
        if "ভিডিও" not in proc["text"]:
            fails.append(f"processing text does not name the video: {proc['text']!r}")

        sent = await pg.evaluate("()=>{const t=document.getElementById('attach-tray');const a=t.querySelector('.att');return a? a.classList.contains('sent'):null;}")
        print("attachment marked sent:", sent)
        if sent is False:
            fails.append("attachment was never marked as sent")

        await pg.wait_for_timeout(8000)
        body = captured.get("body")
        if not body:
            fails.append("no /api/chat request was sent at all")
        else:
            msg = json.loads(body)["messages"][-1]
            content = msg.get("content")
            kinds = [c.get("type") for c in content] if isinstance(content, list) else ["text"]
            print("request content parts:", kinds)
            if "video_url" not in kinds:
                fails.append(f"video part missing from request: {kinds}")
            data = json.dumps(content)
            if "data:video/mp4;base64," not in data:
                fails.append("video part carried no real base64 payload")
            print(f"request size: {len(body)/1024:.0f} KB")

        # the bubble must show the media that was sent
        media = await pg.evaluate("()=>document.querySelectorAll('.msg.user .msg-media').length")
        print("user bubbles showing media:", media)
        if media < 1:
            fails.append("sent media is not shown in the chat bubble")

        await b.close()
    print("FAILS:", fails if fails else "none")
    return 1 if fails else 0


raise SystemExit(asyncio.run(main()))
