"""Capture a screenshot of every ZYRAXON screen into public/shots/ (gallery)."""
import asyncio, pathlib
from playwright.async_api import async_playwright

BASE = "http://127.0.0.1:12000/"
OUT = pathlib.Path(__file__).resolve().parents[1] / "public" / "shots"
OUT.mkdir(parents=True, exist_ok=True)

SHOTS = []


async def snap(page, name, title, note):
    await page.wait_for_timeout(700)
    p = OUT / f"{name}.png"
    await page.screenshot(path=str(p))
    SHOTS.append((name, title, note))
    print("shot:", name, flush=True)


async def main():
    async with async_playwright() as pw:
        b = await pw.chromium.launch(args=["--no-sandbox", "--use-gl=swiftshader", "--enable-unsafe-swiftshader"])
        ctx = await b.new_context(viewport={"width": 412, "height": 915}, device_scale_factor=2, is_mobile=True, has_touch=True)
        page = await ctx.new_page()
        errs = []
        page.on("pageerror", lambda e: errs.append(str(e)))
        await page.goto(BASE, wait_until="domcontentloaded")
        await page.wait_for_timeout(2200)

        # seed a believable conversation + memory + todos through the real layers
        await page.evaluate("""async () => {
          const D = window.ZY.DB;
          await D.remember('প্রিয়_রঙ', 'গাঢ় নীল আর সোনালি', 'preference');
          await D.remember('প্রিয়_খাবার', 'বিরিয়ানি', 'preference');
          await D.remember('নাম', 'জান', 'profile');
          const convo = [
            ['user', 'আমার একটা ওয়েবসাইট বানিয়ে পাবলিশ করো, লেখা থাকবে শুভ রাত্রি।'],
            ['assistant', 'আচ্ছা বাবু, বানিয়ে দিচ্ছি তোমার জন্য একটু অপেক্ষা করো।'],
            ['user', 'তাড়াতাড়ি করো, প্লিজ।'],
            ['assistant', 'হয়ে গেছে! তোমার সাইট লাইভ: shubho-ratri — এখনই খুলে দেখো।'],
          ];
          for (const [role, content] of convo) await D.addMessage({ role, content, ts: Date.now() });
          await D.saveTodos([
            { id: 't1', title: 'প্ল্যান করা', status: 'done' },
            { id: 't2', title: 'index.html তৈরি', status: 'done' },
            { id: 't3', title: 'পাবলিশ করা', status: 'in_progress' },
            { id: 't4', title: 'লাইভ URL ভেরিফাই', status: 'pending' },
          ]);
        }""")
        await page.reload(wait_until="domcontentloaded")
        await page.wait_for_timeout(2500)

        # 1. home / voice hall
        await snap(page, "01-home", "হোম — ভয়েস হল", "ওরা অ্যানিমেশন, লাইভ ক্যাপশন, চরিত্র চিপ, মডেল সিলেক্টর")

        # 2. model picker open (fastest first)
        await page.click("#model-btn")
        await snap(page, "02-models", "মডেল সিলেক্টর", "দ্রুততম মডেল সবার আগে — বেঞ্চমার্ক অনুযায়ী সাজানো")
        await page.keyboard.press("Escape")
        await page.evaluate("document.getElementById('model-menu').hidden = true")

        # 3. chat with restored history
        await page.evaluate("() => { const s = document.getElementById('stage'); s.scrollTop = s.scrollHeight; }")
        await snap(page, "03-chat", "চ্যাট + ইতিহাস", "অ্যাপ বন্ধ করলেও আগের কথা ফিরে আসে (লোকাল DB)")

        # 4. live thinking strip while streaming
        await page.evaluate("""() => {
          const t = document.getElementById('think-strip'); t.hidden = false;
          document.getElementById('think-text').textContent = 'ভাবছি… স্ক্রিন দেখে বুঝে নিচ্ছি';
          document.getElementById('live-badge').textContent = 'শুনছি';
          document.getElementById('live-text').textContent = 'তোমার সাইটটা পাবলিশ করে দিচ্ছি…';
          window.ZY.ORB.setState('thinking');
        }""")
        await snap(page, "04-thinking", "লাইভ থিংকিং", "মাইক অন থাকা অবস্থায় রিয়েল-টাইম স্টেট ও ক্যাপশন")

        # 5. task board + tool log
        await page.evaluate("""() => {
          window.ZY.ui.renderTodos(window.ZY.store.get('todos', []));
          const tl = document.getElementById('toollog'); tl.hidden = false;
          const rows = [
            ['todo_write', 'ok', '4 টাস্ক লেখা হলো'],
            ['generate_file', 'ok', 'index.html (1.2 KB)'],
            ['publish_website', 'ok', 'shubho-ratri → লাইভ'],
            ['read_url', 'ok', 'HTTP 200 ভেরিফাইড'],
          ];
          document.getElementById('tl-list').innerHTML = rows.map(([n, s, d]) =>
            `<div class="tl-row"><b>${n}</b><span class="tl-ok">${s}</span><i>${d}</i></div>`).join('');
          document.getElementById('tl-count').textContent = String(rows.length);
        }""")
        await snap(page, "05-tasks", "টাস্ক বোর্ড ও টুল রান", "এজেন্ট যতক্ষণ টাস্ক বাকি ততক্ষণ থামে না; প্রতিটা টুলের ফল এখানে")

        # 6. artifact panel
        await page.evaluate("""() => {
          window.ZY.ui.showArtifact('index.html', '<!doctype html>\\n<html lang="bn">\\n  <head><meta charset="utf-8">\\n    <title>শুভ রাত্রি</title></head>\\n  <body style="background:#0b1020;color:#e9e6ff">\\n    <h1>শুভ রাত্রি 🌙</h1>\\n  </body>\\n</html>');
        }""")
        await snap(page, "06-artifact", "আর্টিফ্যাক্ট", "তৈরি করা ফাইল সাথে সাথে এখানে — কপি/ওপেন করা যায়")
        await page.evaluate("document.getElementById('artifact').hidden = true")

        # 7-12. drawer panes
        panes = [
            ("settings", "07-settings", "সেটিং", "TTS ভয়েস, মাইক, ব্যাকগ্রাউন্ড, নেটওয়ার্ক"),
            ("permissions", "08-permissions", "অনুমতি", "উপরের বাটনে চাপলেই সিস্টেম পপ-আপ — শুধু Allow"),
            ("memory", "09-memory", "স্মৃতি", "লোকাল DB — ক্লাউড মুছে গেলেও মনে থাকে"),
            ("characters", "10-characters", "চরিত্র", "প্রেমিকা / বন্ধু / বন্ধু-ছেলে — টেক্সট ফাইলের রুলস"),
            ("history", "11-history", "চ্যাট ইতিহাস", "সব পুরনো কথা তারিখসহ"),
            ("skills", "12-skills", "কী কী পারে", "রিয়েল টুল ম্যানুয়াল + প্লে প্রোটেক্ট নোট"),
        ]
        for pane, name, title, note in panes:
            await page.evaluate(f"window.ZY.ui.openDrawer('{pane}')")
            await snap(page, name, title, note)
            await page.evaluate("window.ZY.ui.closeDrawer()")
            await page.wait_for_timeout(200)

        # 13. android permission popup sheet
        await page.evaluate("""() => {
          document.getElementById('sheet-host').hidden = false;
          document.getElementById('sheet-title').textContent = 'মাইক অনুমতি দরকার';
          document.getElementById('sheet-text').textContent = 'বাংলায় কথা বলতে মাইক দরকার। Allow চাপো — ভেতরে ঢুকতে হবে না।';
        }""")
        await snap(page, "13-permission-popup", "পারমিশন পপ-আপ", "ক্লিক করলেই উপরে পপ-আপ — শুধু Allow চাপবে")
        await page.evaluate("document.getElementById('sheet-host').hidden = true")

        # 14. toast
        await page.evaluate("window.ZY.ui.toast('4টি আগের কথা মনে পড়েছে ✓')")
        await snap(page, "14-toast", "টোস্ট", "ছোট নোটিফিকেশন")

        # 15. tool call flow with real SSE run (best effort)
        await page.evaluate("document.getElementById('toollog').hidden = true")
        await page.fill("#input", "বলো সংক্ষেপে তুমি কী কী পারো")
        await page.click("#btn-send")
        await page.wait_for_timeout(2500)
        await snap(page, "15-live-run", "লাইভ রান (রিয়েল SSE)", "টোকেন স্ট্রিম হচ্ছে — সাথে সাথে দেখা যায়")
        await page.wait_for_timeout(20000)

        # 16. the five orb animation styles, tiled into one sheet
        await page.evaluate("document.getElementById('stage').scrollTop = 0")
        await page.evaluate("document.getElementById('artifact').hidden = true")
        await page.evaluate("document.getElementById('toollog').hidden = true")
        await page.evaluate("document.getElementById('taskboard').hidden = true")
        await page.wait_for_timeout(900)
        orb = await page.locator("#orb-btn").bounding_box()
        tiles = []
        for st in ["halo", "pulse", "orbit", "wave", "bloom"]:
            await page.evaluate(f"window.ZY.ORB.setStyle('{st}')")
            await page.evaluate("window.ZY.ORB.setState('speaking'); window.ZY.ORB.setLevel(0.7)")
            await page.wait_for_timeout(700)
            p = OUT / f"_orb_{st}.png"
            await page.screenshot(path=str(p), clip=orb)
            tiles.append((p, st))
        from PIL import Image, ImageDraw
        ims = [(Image.open(p).convert("RGB"), st) for p, st in tiles]
        w, h = ims[0][0].size
        sheet = Image.new("RGB", (w * len(ims), h + 44), (8, 9, 22))
        d = ImageDraw.Draw(sheet)
        for i, (im, st) in enumerate(ims):
            sheet.paste(im, (i * w, 44))
            d.text((i * w + 12, 14), st, fill=(240, 240, 255))
        sheet.save(OUT / "16-orb-styles.png")
        for p, _ in tiles:
            p.unlink(missing_ok=True)
        SHOTS.append(("16-orb-styles", "ওরা — ৫টি অ্যানিমেশন", "হ্যালো · নাড়ি · অরবিট · তরঙ্গ · ব্লুম — সেটিং থেকে বদলাও"))
        print("shot: 16-orb-styles", flush=True)
        await page.evaluate("window.ZY.ORB.setStyle('halo')")

        # 17. live speech box (what the assistant is saying, in real time)
        await page.evaluate("""() => {
          window.ZY.ui.setSpeak('তোমার সাইটটা পাবলিশ হয়ে গেছে — এখনই খুলে দেখো, বাবু।', true);
          document.getElementById('live-badge').textContent = 'বলছি…';
        }""")
        await snap(page, "17-speak-box", "লাইভ স্পিচ বক্স", "সে যা বলছে, ঠিক সেটাই এখানে রিয়েল-টাইমে দেখা যায়")
        await page.evaluate("document.getElementById('speak-box').hidden = true")

        # 18. attachments: image / video / audio go to the model as real parts
        await page.evaluate("""() => {
          const px = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==';
          window.ZY.Agent.attachments = [
            { kind: 'image', dataUrl: px, name: 'ছবি.png' },
            { kind: 'video', dataUrl: px, name: 'ক্লিপ.mp4' },
            { kind: 'audio', dataUrl: px, name: 'ভয়েস.wav', format: 'wav' },
          ];
          document.getElementById('attach-tray').hidden = false;
          document.getElementById('attach-tray').innerHTML = ['ছবি.png','ক্লিপ.mp4','ভয়েস.wav'].map((n,i)=>
            `<div class="att">${['🖼️','🎬','🎵'][i]}<span class="x">✕</span></div>`).join('');
        }""")
        await snap(page, "18-attachments", "ছবি / ভিডিও / অডিও পাঠাও", "প্রতিটি মডেল মাল্টিমোডাল — সরাসরি মডেলে যায়")
        await page.evaluate("document.getElementById('attach-tray').hidden = true")

        print("PAGE ERRORS:", errs[:5], flush=True)
        await b.close()

    rows = "\n".join(
        f'<a class="card" href="{n}.png" target="_blank"><img src="{n}.png" alt="{t}"><div><b>{t}</b><span>{note}</span></div></a>'
        for n, t, note in SHOTS
    )
    (OUT / "index.html").write_text(f"""<!doctype html>
<html lang="bn"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>ZYRAXON — সব স্ক্রিন</title>
<style>
  :root{{--bg:#07070d;--acc:#8b7bff;--acc2:#ff7bc6}}
  *{{box-sizing:border-box}}
  body{{margin:0;background:radial-gradient(1200px 800px at 20% -10%,#1a1440,transparent),radial-gradient(900px 700px at 100% 0%,#3a1140,transparent),var(--bg);color:#ecebff;
    font-family:'Noto Sans Bengali',system-ui,-apple-system,'Segoe UI',sans-serif;padding:28px 18px 60px}}
  header{{max-width:1180px;margin:0 auto 22px}}
  h1{{margin:0 0 6px;font-size:clamp(24px,4vw,40px);background:linear-gradient(90deg,var(--acc),var(--acc2));-webkit-background-clip:text;background-clip:text;color:transparent}}
  p.sub{{margin:0;opacity:.75}}
  .grid{{max-width:1180px;margin:0 auto;display:grid;gap:20px;grid-template-columns:repeat(auto-fill,minmax(260px,1fr))}}
  .card{{display:flex;flex-direction:column;gap:10px;padding:12px;border-radius:18px;text-decoration:none;color:inherit;
    background:rgba(255,255,255,.045);border:1px solid rgba(255,255,255,.09);backdrop-filter:blur(14px);transition:.25s}}
  .card:hover{{transform:translateY(-4px);border-color:rgba(139,123,255,.55);box-shadow:0 18px 40px rgba(0,0,0,.5)}}
  .card img{{width:100%;border-radius:12px;display:block;border:1px solid rgba(255,255,255,.08)}}
  .card b{{display:block;font-size:15px}}
  .card span{{display:block;font-size:12.5px;opacity:.7;margin-top:3px;line-height:1.5}}
</style></head>
<body>
<header><h1>ZYRAXON — পুরো অ্যাপ্লিকেশনের স্ক্রিন</h1>
<p class="sub">{len(SHOTS)}টি স্ক্রিন · মোবাইল ভিউ (412×915) · রিয়েল অ্যাপ থেকে তোলা</p></header>
<div class="grid">{rows}</div>
</body></html>""")
    print("gallery:", OUT / "index.html", flush=True)


asyncio.run(main())
