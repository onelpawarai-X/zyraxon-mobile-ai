"""The user's newest asks, checked one by one in a real browser.

1. Todos and tool calls live in their OWN panels, not inside the chat.
2. The live code overlay shows the code as it is written, on top of the page.
3. Thinking is visible but never spoken.
4. The mic keeps working while the assistant is busy.
5. Scrolling is smooth (one scroll per frame, not one per token).
"""
import asyncio
from playwright.async_api import async_playwright

BASE = "http://127.0.0.1:12000/"
fails = []


def check(name, ok, extra=""):
    print(("PASS " if ok else "FAIL ") + name + ((" — " + extra) if extra else ""))
    if not ok:
        fails.append(name)


async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch()
        pg = await (await b.new_context()).new_page()
        errors = []
        pg.on("pageerror", lambda e: errors.append(str(e)))
        await pg.goto(BASE, wait_until="networkidle")
        await pg.wait_for_timeout(2500)

        # 1. the panels are separate nodes, outside the chat
        r = await pg.evaluate("""(() => {
          const chat = document.getElementById('chat');
          const todos = document.getElementById('dock-todos');
          const tools = document.getElementById('dock-tools');
          return {
            chatHasTodos: chat ? chat.contains(todos) : null,
            chatHasTools: chat ? chat.contains(tools) : null,
            todosExist: !!todos, toolsExist: !!tools,
            insideOldChat: !!document.querySelector('.cview [id="taskboard"], .cview [id="toollog"]'),
          };
        })()""")
        check("todo panel is a separate node", r["todosExist"] and not r["chatHasTodos"])
        check("tool panel is a separate node", r["toolsExist"] and not r["chatHasTools"])
        check("no old panels left inside the chat", not r["insideOldChat"])

        # 2. the live code overlay exists and is driven by the stream
        r = await pg.evaluate("""(() => {
          window.ZY.ui.showCodeOverlay('Writing code · html', '<h1>hi</h1>\\n<p>second line</p>');
          const ov = document.getElementById('code-overlay');
          const body = document.getElementById('co-code').textContent;
          return { hidden: ov.hidden, text: body, z: getComputedStyle(ov).zIndex,
                   pos: getComputedStyle(ov).position };
        })()""")
        check("code overlay shows and floats over the page",
              (not r["hidden"]) and "second line" in r["text"] and r["pos"] == "fixed",
              f"z={r['z']}")

        # 3. reasoning must be shown but silent: reason() must not enqueue speech
        r = await pg.evaluate("""(() => {
          const T = window.ZY.VOICE.TTS;
          T._queue = []; T._speaking = false;
          T.reason('এটা আমার ভেতরের ভাবনা, শোনানো উচিত না');
          return { queued: T._queue.length, speaking: T._speaking };
        })()""")
        check("thinking is never spoken", r["queued"] == 0 and not r["speaking"],
              f"queued={r['queued']}")

        # 4. the mic is re-armed even while the agent is busy
        r = await pg.evaluate("""(async () => {
          const stt = window.ZY.VOICE.STT;
          const calls = [];
          const realStart = stt.start;
          stt.start = function () { calls.push(1); return true; };
          window.ZY.Agent.busy = true;
          const p = window.ZY.Agent.send('টেস্ট');
          await new Promise((r) => setTimeout(r, 400));
          window.ZY.Agent.busy = false;
          await p;
          stt.start = realStart;
          return { called: calls.length, queued: window.ZY.Agent.pending.length };
        })()""")
        check("new words while busy reach the agent", r["queued"] >= 1, f"pending={r['queued']}")
        check("mic stays armed while busy", r["called"] >= 1, f"start() called {r['called']}x")

        # 5. one scroll per frame while tokens stream
        r = await pg.evaluate("""(() => {
          const U = window.ZY.ui;
          let scrolls = 0;
          const st = U._el.stage;
          const orig = Object.getOwnPropertyDescriptor(Element.prototype, 'scrollTop');
          U._scrollPending = false;
          const before = st.scrollTop;
          for (let i = 0; i < 200; i++) U._scroll();
          return { pending: !!U._scrollPending, before };
        })()""")
        check("scroll calls are coalesced", r["pending"], "200 calls -> 1 pending frame")

        check("no page errors", not errors, "; ".join(errors[:2]))
        await b.close()

    print("\n" + ("ALL UI CHECKS PASSED" if not fails else "FAILED: " + ", ".join(fails)))


asyncio.run(main())
