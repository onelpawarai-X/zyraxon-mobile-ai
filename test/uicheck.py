import asyncio, json, os
from playwright.async_api import async_playwright

BASE = os.environ.get("ZYRAXON_BASE_URL", "http://localhost:12000")

async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch()
        ctx = await b.new_context(viewport={"width": 390, "height": 844}, device_scale_factor=2)
        page = await ctx.new_page()
        errs = []
        page.on("console", lambda m: errs.append(m.text) if m.type == "error" else None)
        await page.goto(BASE, wait_until="networkidle")
        await page.wait_for_timeout(1500)

        out = {}
        # PWA
        out["manifest"] = await page.evaluate("""async () => {
          const r = await fetch('/manifest.webmanifest');
          return { status: r.status, ct: r.headers.get('content-type'),
                   name: (await r.json()).name };
        }""")
        out["sw"] = await page.evaluate("navigator.serviceWorker.controller ? 'active' : 'registered'")

        # Models: fastest first, 10 entries
        await page.click("#model-btn"); await page.wait_for_timeout(300)
        out["model_count"] = await page.locator(".model-item").count()
        out["model_first"] = (await page.locator(".model-item").first.inner_text()).splitlines()[0]
        out["model_order"] = await page.locator(".model-item").all_inner_texts()
        await page.keyboard.press("Escape")

        # Characters present + switch persists
        out["chars"] = await page.evaluate("Object.keys(window.ZY.characters)")
        await page.evaluate("window.ZY.ui.openDrawer('characters')")
        await page.wait_for_timeout(300)
        out["char_sheet_open"] = await page.evaluate("!document.querySelector('#drawer').hidden")

        # Background scenes selectable
        out["scenes"] = await page.evaluate("window.ZY.BG.scenes.map(s => s.name)")

        # Tools catalogue
        out["tool_count"] = await page.evaluate("window.ZY.Tools.names().length")

        # TTS: Bangla voice availability (headless may have none; report)
        out["voices"] = await page.evaluate("""() => {
          const vs = speechSynthesis.getVoices();
          const bn = vs.filter(v => /bn|bengali/i.test(v.lang + ' ' + v.name)).map(v => v.name + '|' + v.lang);
          return { total: vs.length, bn, all: vs.slice(0,6).map(v => v.lang) };
        }""")

        # Permission sheet: clicking a permission row shows an allow popup, no navigation away
        await page.evaluate("window.ZY.ui.openDrawer('permissions')")
        await page.wait_for_timeout(400)
        rows = await page.locator("#drawer .perm-row, #drawer .row").count()
        out["perm_rows"] = rows

        # SSE streaming: badge + thinking visible during a short turn
        await page.evaluate("window.ZY.ui.closeDrawer()")
        # The composer lives inside the console sheet, so the sheet must be open
        # before #input is visible.
        await page.click("#btn-console")
        await page.wait_for_selector("#input", state="visible", timeout=10000)
        await page.evaluate("window.__spoke=[]; const sp=speechSynthesis.speak.bind(speechSynthesis); speechSynthesis.speak=(u)=>{window.__spoke.push(String((u&&u.text)||'').slice(0,60)); try{return sp(u);}catch(e){return null;}}")
        await page.fill("#input", "হ্যালো, কেমন আছো?")
        await page.evaluate("window.__remoteTTS=[]; const OA=window.Audio; window.Audio=function(u){ window.__remoteTTS.push(String(u)); return new OA(u); }; window.Audio.prototype=OA.prototype;")
        await page.click("#btn-send")
        await page.wait_for_timeout(1500)
        out["streaming_badge"] = await page.locator("#live-badge").inner_text()
        streamed = 0
        for _ in range(60):
            await page.wait_for_timeout(500)
            if not await page.locator("#btn-stop").is_hidden():
                streamed = await page.locator(".msg.ai .body").count()
            else:
                break
        out["assistant_msgs"] = await page.locator(".msg.ai .body").count()
        out["tts_spoken"] = await page.evaluate("window.__spoke || []")
        out["tts_remote"] = await page.evaluate("window.__remoteTTS || []")
        out["errors"] = errs[:5]
        print(json.dumps(out, ensure_ascii=False, indent=2))
        await b.close()

asyncio.run(main())
