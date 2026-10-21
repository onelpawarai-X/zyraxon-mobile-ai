import asyncio, os, json
from playwright.async_api import async_playwright
BASE = os.environ.get("ZYRAXON_BASE_URL", "http://localhost:12000")

async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch(args=["--no-sandbox"])
        pg = await (await b.new_context(viewport={"width": 412, "height": 915})).new_page()
        log = []
        pg.on("console", lambda m: log.append(f"{m.type}: {m.text}"))
        pg.on("pageerror", lambda e: log.append("PAGEERROR: " + str(e)))
        pg.on("request", lambda r: log.append("REQ " + r.method + " " + r.url) if "/api/" in r.url else None)
        pg.on("response", lambda r: log.append(f"RES {r.status} {r.url}") if "/api/" in r.url else None)
        pg.on("requestfailed", lambda r: log.append(f"FAILED {r.url} {r.failure}"))
        await pg.add_init_script("localStorage.setItem('zyraxon:settings', JSON.stringify({tts:false,automic:false}));")
        await pg.goto(BASE, wait_until="networkidle")
        await pg.wait_for_timeout(1200)

        await pg.fill("#input", "একটা ছোট ওয়েবসাইট বানিয়ে পাবলিশ করো যেখানে লেখা থাকবে হ্যালো জাইরাক্সন।")
        await pg.click("#btn-send")
        for i in range(30):
            await pg.wait_for_timeout(3000)
            st = await pg.evaluate("""() => ({
              busy: window.ZY.Agent.busy, hist: window.ZY.Agent.history.length,
              tools: document.querySelectorAll('#tl-list .tl-row').length,
              msgs: document.querySelectorAll('.msg').length,
              toast: (document.querySelector('.toast')||{}).textContent || '',
              stopHidden: document.getElementById('btn-stop').hidden,
            })""")
            print(i, st, flush=True)
            if not st["busy"] and st["msgs"] > 1:
                break
        dump = await pg.evaluate("""() => ({
          toolCalls: window.ZY.Agent.history.filter(m => m.role==='assistant' && m.tool_calls).map(m => m.tool_calls.map(t => ({name:t.function.name, args:t.function.arguments}))),
          toolResults: window.ZY.Agent.history.filter(m => m.role==='tool').map(m => (m.content||'').slice(0,300)),
          todos: window.ZY.store.get('todos', []),
          boardHidden: document.getElementById('taskboard').hidden,
        })""")
        print("--- TOOL CALLS ---")
        for c in dump["toolCalls"]:
            print("  ", json.dumps(c, ensure_ascii=False)[:600])
        print("--- TOOL RESULTS ---")
        for c in dump["toolResults"]:
            print("  ", c)
        print("--- TODOS ---", json.dumps(dump["todos"], ensure_ascii=False)[:400], "boardHidden=", dump["boardHidden"])
        await b.close()

asyncio.run(main())
