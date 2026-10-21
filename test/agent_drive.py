"""Drive the app's OWN agent through the chat UI and watch what it does.

Nothing here calls a tool directly: every action comes from the assistant
inside the app, exactly as the user would trigger it.
"""
import asyncio, sys, time
from playwright.async_api import async_playwright

BASE = "https://work-1-xjvfsfgnuxzdemie.prod-runtime.all-hands.dev/"
PROMPT = sys.argv[1] if len(sys.argv) > 1 else "আমার গিটহাবে একটা ওয়েবসাইট পাবলিশ করে দাও"
LIMIT = int(sys.argv[2]) if len(sys.argv) > 2 else 900


async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch()
        ctx = await b.new_context(viewport={"width": 412, "height": 915})
        pg = await ctx.new_page()
        errs = []
        pg.on("pageerror", lambda e: errs.append(str(e)))
        await pg.goto(BASE, wait_until="networkidle")
        await pg.wait_for_timeout(3000)
        await pg.click("#btn-console")
        await pg.wait_for_timeout(400)
        await pg.fill("#input", PROMPT)
        await pg.click("#btn-send")
        print("PROMPT:", PROMPT, flush=True)

        t0 = time.time()
        last = ""
        while time.time() - t0 < LIMIT:
            await pg.wait_for_timeout(2000)
            state = await pg.evaluate("""(() => {
              const ai = document.querySelectorAll('.msg.ai .body');
              const tools = [...document.querySelectorAll('#tl-list .tl-name')].map(e=>e.textContent);
              const todos = [...document.querySelectorAll('#tb-list .tb-item')].map(e=>e.innerText.replace(/\\s+/g,' ').trim());
              const art = document.getElementById('artifact');
              return { busy: !!window.ZY.Agent.busy, tools: tools.length,
                       lastTool: tools[tools.length-1] || '', todos: todos.length,
                       text: ai.length ? ai[ai.length-1].textContent.slice(-260) : '',
                       mon: (()=>{const m=document.getElementById('monitorbar');return m&&!m.hidden?document.getElementById('mon-text').textContent:''})(),
                       artOpen: !!(art && !art.hidden), artTitle: document.getElementById('artifact-title').textContent };
            })()""")
            line = f"t={int(time.time()-t0)}s busy={state['busy']} tools={state['tools']} last={state['lastTool']} todos={state['todos']} mon={state['mon'][:70]!r}"
            if line != last:
                print(line, flush=True)
                last = line
            if not state["busy"] and state["tools"] >= 0 and time.time() - t0 > 20:
                # give a couple of extra beats for the final turn
                await pg.wait_for_timeout(3000)
                if not await pg.evaluate("!!window.ZY.Agent.busy"):
                    break

        final = await pg.evaluate("""(() => {
          const ai = document.querySelectorAll('.msg.ai .body');
          const tools = [...document.querySelectorAll('#tl-list .tl-name')].map(e=>e.textContent);
          const sums = [...document.querySelectorAll('#tl-list .tl-sum')].map(e=>e.textContent);
          return { tools, sums, text: ai.length ? ai[ai.length-1].textContent : '',
                   artifact: (()=>{const a=document.getElementById('artifact');return a&&!a.hidden?document.getElementById('artifact-body').textContent.slice(0,400):''})(),
                   artifactTitle: document.getElementById('artifact-title').textContent };
        })()""")
        print("\n=== TOOLS ===", final["tools"])
        for s in final["sums"]:
            print("   -", s[:170])
        print("\n=== FINAL REPLY ===\n", final["text"][:1400])
        print("\n=== ARTIFACT ===", final["artifactTitle"], "\n", final["artifact"][:300])
        if errs:
            print("\n=== JS ERRORS ===", errs[:4])
        await b.close()


asyncio.run(main())
