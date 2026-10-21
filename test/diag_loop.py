"""Diagnose why the agent stops mid-task: log every round, every tool call,
every toast and every HTTP response the agent gets.
"""
import asyncio
from playwright.async_api import async_playwright

BASE = "https://work-1-xjvfsfgnuxzdemie.prod-runtime.all-hands.dev/"


async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch()
        ctx = await b.new_context(viewport={"width": 412, "height": 915})
        pg = await ctx.new_page()
        events = []
        pg.on("pageerror", lambda e: events.append("PAGEERROR " + str(e)))
        pg.on("console", lambda m: events.append(f"{m.type} {m.text}"))
        pg.on("response", lambda r: events.append(f"HTTP {r.status} {r.url[-40:]}") if "/api/chat" in r.url else None)
        await pg.add_init_script("""
          window.__rounds = [];
          window.__toasts = [];
        """)
        await pg.goto(BASE, wait_until="networkidle")
        await pg.wait_for_timeout(3000)
        # hook the toast + agent round so we can see exactly where it stops
        await pg.evaluate("""
          const origToast = window.ZY.ui.toast.bind(window.ZY.ui);
          window.ZY.ui.toast = (m) => { window.__toasts.push(m); return origToast(m); };
          const A = window.ZY.Agent;
          const origStream = A._stream.bind(A);
          let n = 0;
          A._stream = async (msgs, opts) => {
            n++;
            const r = await origStream(msgs, opts);
            window.__rounds.push({
              round: n,
              model: r.model,
              content: (r.content||'').slice(0,200),
              tools: r.toolCalls.map(t=>t.function.name),
              msgs: msgs.length,
              chars: JSON.stringify(msgs).length,
              reasoning: (r.reasoning||'').slice(0,120),
            });
            return r;
          };
          true;
        """)
        await pg.click("#btn-console")
        await pg.wait_for_timeout(400)
        await pg.fill("#input", "Build me a small website about the moon and publish it.")
        await pg.click("#btn-send")
        for _ in range(240):
            await pg.wait_for_timeout(500)
            if not await pg.evaluate("!!window.ZY.Agent.busy"):
                break
        rounds = await pg.evaluate("window.__rounds")
        print("ROUNDS:", len(rounds))
        for r in rounds:
            print(f"  #{r['round']} model={r['model']} msgs={r['msgs']} chars={r['chars']}")
            print(f"     tools={r['tools']}")
            print(f"     content={r['content']!r}")
            print(f"     reasoning={r['reasoning']!r}")
        print("TOASTS:", await pg.evaluate("window.__toasts"))
        print("TODOS:", await pg.evaluate("[...document.querySelectorAll('#tb-list .tb-item')].map(e=>e.innerText)"))
        print("EVENTS:", [e for e in events if "error" in e.lower() or "HTTP" in e][:20])
        await b.close()


asyncio.run(main())
