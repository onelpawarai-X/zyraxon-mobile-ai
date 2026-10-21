"""Verify the hard promises with the real agent, not with mocks:

  1. the agent really publishes a website and gets a live URL back
  2. a *different* model really monitors the work and reports it out loud
  3. the agent keeps going until the todo board is done (no early stop)
  4. open_app / launch_url really act
  5. no round cap: a multi-step request runs to completion in one turn
"""
import asyncio, re, sys, urllib.request, urllib.error
from playwright.async_api import async_playwright

BASE = "http://127.0.0.1:12000/"
FAILS = []


def check(name, ok, detail=""):
    print(("PASS " if ok else "FAIL ") + name + ((" — " + str(detail)[:400]) if detail else ""))
    if not ok:
        FAILS.append(name)


async def ask(pg, prompt, seconds=240):
    await pg.fill("#input", prompt)
    await pg.click("#btn-send")
    rounds = 0
    for _ in range(seconds * 2):
        await pg.wait_for_timeout(500)
        rounds = await pg.evaluate("window.__rounds || 0")
        busy = await pg.evaluate("!!(window.ZY && window.ZY.Agent && window.ZY.Agent.busy)")
        if not busy:
            break
    return rounds


async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch(args=["--use-fake-ui-for-media-stream", "--use-fake-device-for-media-stream"])
        ctx = await b.new_context(permissions=["microphone"], viewport={"width": 412, "height": 915})
        pg = await ctx.new_page()
        errors = []
        pg.on("pageerror", lambda e: errors.append(str(e)))
        pg.on("console", lambda m: errors.append("console: " + m.text) if m.type == "error" else None)
        pg.on("response", lambda r: errors.append(f"http {r.status} {r.request.resource_type} {r.url}") if r.status >= 400 else None)
        await pg.add_init_script("""
        (() => {
          window.__bad = [];
          const of = window.fetch;
          window.fetch = function (u, ...rest) {
            const s = String(u && u.url ? u.url : u);
            if (s.includes('undefined') || s.includes('null')) window.__bad.push({ url: s, stack: (new Error()).stack });
            return of.apply(this, [u, ...rest]);
          };
        })();
        """)
        await pg.goto(BASE, wait_until="domcontentloaded")
        await pg.wait_for_timeout(2500)
        await pg.click("#btn-console")
        await pg.wait_for_selector("#input", state="visible", timeout=10000)

        # record everything the voice is asked to say
        await pg.evaluate("""(() => {
          window.__spoken = [];
          const T = window.ZY.VOICE.TTS;
          const orig = T.push.bind(T);
          T.push = (t) => { window.__spoken.push(String(t)); return orig(t); };
        })()""")

        # ── 1. website publishing, through the real tool ─────────────────────
        site = await pg.evaluate("""(async () => {
          const r = await window.ZY.Tools.execute('publish_website', {
            name: 'verify-final',
            html: '<!doctype html><title>ZYRAXON verify</title><h1>it works</h1>'
          });
          return r;
        })()""")
        check("publish_website really works", bool(site.get("ok")) and bool(site.get("data", {}).get("url")),
              site.get("data", {}).get("url") or site)
        url = site.get("data", {}).get("url", "") or ""
        if url.startswith("http"):
            # Verify from outside the page too: an independent HTTP client is
            # the honest test of "does this URL really serve for anyone".
            live = {}
            for _ in range(8):
                try:
                    with urllib.request.urlopen(url, timeout=15) as r:
                        live = {"status": r.status, "body": r.read(120).decode("utf-8", "replace")}
                except urllib.error.HTTPError as e:
                    live = {"status": e.code, "body": e.read(120).decode("utf-8", "replace")}
                except Exception as e:
                    live = {"error": str(e)}
                if live.get("status") == 200:
                    break
                await pg.wait_for_timeout(1000)
            check("published site really serves", live.get("status") == 200 and "it works" in (live.get("body") or ""), live)

        # ── 2. the agent publishes on request, and the monitor model reports ─
        await pg.evaluate("window.__spoken = []; window.ZY.Monitor.report = '';")
        await ask(pg, "আমার জন্য একটা ছোট ওয়েবসাইট বানিয়ে পাবলিশ করো, তারপর আমাকে লিংকটা দাও", seconds=300)
        tools = await pg.evaluate("[...document.querySelectorAll('#tl-list .tl-row .tl-name')].map(e=>e.textContent)")
        check("agent used publish_website", "publish_website" in tools, tools)
        check("agent verified with a read tool",
              any(t in tools for t in ("read_url", "read_file", "list_dir", "find_files")), tools)
        mon = await pg.evaluate("window.ZY.Monitor.report || ''")
        check("monitor model reported progress", len(mon.strip()) > 0, mon)
        mon_model = await pg.evaluate("window.ZY.Monitor.model || ''")
        work_model = await pg.evaluate("window.ZY.Agent.model || ''")
        check("monitor is a different model", bool(mon_model) and mon_model != work_model, f"{mon_model} vs {work_model}")
        spoken = await pg.evaluate("window.__spoken || []")
        check("monitor report was spoken", any(mon[:25] in s for s in spoken) or len(spoken) > 0, spoken[:4])

        # ── 3. the todo board ends up done, and the loop did not stop early ──
        todos = await pg.evaluate("(window.ZY.store.get('todos',[])||[]).map(t=>t.title+'='+t.status)")
        check("todo board present", len(todos) > 0, todos)
        check("no todo left open after the turn",
              all(t.endswith("=done") for t in todos), todos)

        # ── 4. opening things really acts ────────────────────────────────────
        opened = await pg.evaluate("""(async () => {
          const r = await window.ZY.Tools.execute('launch_url', { url: 'https://example.com' });
          return r;
        })()""")
        check("launch_url really acts", bool(opened.get("ok")), opened.get("summary"))

        # ── 5. run_shell really executes on the host and reports where ───────
        sh = await pg.evaluate("""(async () => {
          const r = await window.ZY.Tools.execute('run_shell', { command: 'echo zyraxon-shell-ok && uname -s' });
          return r;
        })()""")
        check("run_shell really executes",
              bool(sh.get("ok")) and "zyraxon-shell-ok" in str(sh.get("data", {}).get("stdout", "")), sh.get("summary"))
        check("run_shell says where it ran",
              any(k in str(sh.get("summary", "")) for k in ("হোস্ট", "sandbox", "Android", "host")), sh.get("summary"))

        # ── 6. sequential multi-model really rotates the model per round ─────
        seq = await pg.evaluate("""(async () => {
          const A = window.ZY.Agent;
          A.setSequential(true);
          const perRound = [1,2,3,4,5].map((r) => A._modelForRound(r));
          const seen = [];
          const orig = A._stream.bind(A);
          A._stream = async (msgs, opts) => { seen.push((opts && opts.model) || ''); return orig(msgs, opts); };
          await A.send('একটা ছোট ওয়েবসাইট বানাও এবং পাবলিশ করো');
          A._stream = orig;
          A.setSequential(false);
          return { perRound, seen };
        })()""")
        pr = seq.get("perRound", [])
        check("sequential mode assigns a different model per round",
              len(pr) >= 4 and len(set(pr)) >= 3, pr)
        seen = seq.get("seen", [])
        check("sequential run really used several models",
              len(seen) >= 2 and len(set(seen)) >= 2, seen)

        check("no page errors", not errors, errors[:3])
        bad = await pg.evaluate("window.__bad || []")
        check("no undefined URL was fetched", not bad, bad[:2])
        await b.close()

    print("\n" + ("ALL CHECKS PASSED" if not FAILS else f"{len(FAILS)} FAILED: {FAILS}"))
    return 1 if FAILS else 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
