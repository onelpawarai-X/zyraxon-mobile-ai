"""Can ZYRAXON really build and publish a website by itself?

The user's own provider answers, the real tools run on the real machine, and
the published URL is fetched from the internet afterwards. Nothing here is
mocked: if the agent says it published a site, that site must answer 200 with
the content the agent wrote.
"""
import asyncio, re, urllib.request
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
          const T = window.ZY.Tools;
          const orig = T.execute;
          T.execute = async function (name, args) {
            const r = await orig.apply(this, arguments);
            window.__tools.push({ name, args, ok: !(r && r.ok === false),
                                  summary: (r && r.summary) || '', url: (r && r.data && r.data.url) || '' });
            return r;
          };
        })()""")

        await pg.evaluate("""window.ZY.Agent.send(
          'একটা ছোট ওয়েবসাইট বানাও — একটা index.html যেখানে শিরোনাম "ZYRAXON Live Test" ' +
          'আর একটা h1 লেখা "hello from zyraxon"। তারপর সেটা publish_website দিয়ে পাবলিশ করো ' +
          'এবং লাইভ URL আমাকে দাও।')""")

        for _ in range(60):
            await pg.wait_for_timeout(3000)
            done = await pg.evaluate("!window.ZY.Agent.busy")
            if done:
                break

        out = await pg.evaluate("""(() => ({
          tools: window.__tools.map(t => ({ name: t.name, ok: t.ok, url: t.url, summary: String(t.summary).slice(0, 140) })),
          spoken: (window.ZY.VOICE.TTS._buf || '').slice(-500),
        }))()""")
        names = [t["name"] for t in out["tools"]]
        print("TOOLS CALLED:", names)
        pub = [t for t in out["tools"] if t["name"] in ("publish_website", "github_publish") and t["ok"]]
        print("PUBLISH RESULTS:")
        for t in pub:
            print("  ", t["url"] or t["summary"])
        urls = [t["url"] for t in pub if t["url"]]
        urls += re.findall(r"https?://[^\s\"'`]+", out["spoken"])
        urls = [u.rstrip("।,.)") for u in urls if "sites/" in u or "github.io" in u]
        print("URLS SEEN:", urls[:3])
        good = False
        for u in urls[:3]:
            try:
                body = urllib.request.urlopen(u, timeout=45).read().decode("utf8", "ignore")
                print(f"  fetched {u} -> {len(body)} bytes")
                if "zyraxon" in body.lower() or "hello" in body.lower():
                    good = True
                    break
            except Exception as e:
                print(f"  fetch failed {u}: {e}")
        print("PASS the agent built and published a real live site" if good
              else "FAIL no live site could be fetched")
        await b.close()

asyncio.run(main())
