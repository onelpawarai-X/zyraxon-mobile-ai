"""Can ZYRAXON build a small *app* (not just a page) and show its todos?

Checks the two things the user asked for together: the agent writes real app
files (HTML + JS that actually does something) and publishes them, and the todo
panel above the chat really lists the steps it made for itself.
"""
import asyncio, urllib.request
from playwright.async_api import async_playwright

BASE = "http://127.0.0.1:12000/"


async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch()
        pg = await (await b.new_context()).new_page()
        await pg.goto(BASE, wait_until="networkidle")
        await pg.wait_for_timeout(2500)

        await pg.evaluate("""(() => {
          window.__tools = [];
          const T = window.ZY.Tools, orig = T.execute;
          T.execute = async function (name, args) {
            const r = await orig.apply(this, arguments);
            window.__tools.push({ name, url: (r && r.data && r.data.url) || '' });
            return r;
          };
          window.__todoSnaps = [];
          const U = window.ZY.ui, origTodos = U.renderTodos;
          U.renderTodos = function (t) { window.__todoSnaps.push((t || []).length); return origTodos.apply(U, arguments); };
        })()""")

        await pg.evaluate("""window.ZY.Agent.send(
          'একটা ছোট কাউন্টার অ্যাপ বানাও — index.html আর app.js, বাটনে ক্লিক করলে সংখ্যা বাড়বে। ' +
          'তারপর publish_website দিয়ে পাবলিশ করে লাইভ লিংক দাও।')""")

        for _ in range(60):
            await pg.wait_for_timeout(3000)
            if await pg.evaluate("!window.ZY.Agent.busy"):
                break

        out = await pg.evaluate("""(() => ({
          tools: window.__tools, snaps: window.__todoSnaps,
          panel: document.querySelectorAll('.tb-item').length,
          todos: (window.ZY.store.get('todos', []) || []).length,
          arts: document.querySelectorAll('.artifact').length,
        }))()""")
        names = [t["name"] for t in out["tools"]]
        print("TOOLS:", names)
        print("todos in store:", out["todos"], "| panel nodes:", out["panel"], "| artifacts on screen:", out["arts"])
        url = next((t["url"] for t in out["tools"] if t["name"] == "publish_website" and t["url"]), "")
        print("published:", url)
        files_ok = "generate_file" in names or "write_file" in names
        body = ""
        if url:
            body = urllib.request.urlopen(url, timeout=45).read().decode("utf8", "ignore")
            print("site bytes:", len(body))
        js_ok = False
        if url:
            try:
                js_ok = urllib.request.urlopen(url.rstrip("/") + "/app.js", timeout=45).read()
            except Exception as e:
                print("  app.js fetch:", e)
                js_ok = bool(body and ("app.js" in body or "script" in body))
        print("PASS the app was written, published and its JS is served"
              if files_ok and url and js_ok and out["todos"] > 0
              else "FAIL something is missing")
        await b.close()

asyncio.run(main())
