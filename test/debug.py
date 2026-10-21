import asyncio, os
from playwright.async_api import async_playwright
BASE = os.environ.get("ZYRAXON_BASE_URL", "http://localhost:12000")

async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch(args=["--no-sandbox"])
        pg = await (await b.new_context(viewport={"width": 412, "height": 915})).new_page()
        logs = []
        pg.on("console", lambda m: logs.append(f"{m.type}: {m.text}"))
        pg.on("pageerror", lambda e: logs.append("PAGEERROR: " + str(e)))
        await pg.add_init_script("localStorage.clear()")
        await pg.goto(BASE, wait_until="networkidle")
        await pg.wait_for_timeout(2500)
        state = await pg.evaluate("""() => ({
            hasZY: !!window.ZY,
            keys: window.ZY ? Object.keys(window.ZY) : [],
            agentModel: window.ZY && window.ZY.Agent && window.ZY.Agent.model,
            models: window.ZY && window.ZY.App && window.ZY.App.models && window.ZY.App.models.length,
            modelLabel: document.getElementById('model-label') && document.getElementById('model-label').textContent,
            modelMenuHTML: (document.getElementById('model-menu')||{}).innerHTML ? 'yes' : 'no',
            tools: window.ZY && window.ZY.Tools && window.ZY.Tools.names().length,
            sysPromptLen: window.ZY && window.ZY.Agent && window.ZY.Agent.systemPrompt.length,
            chatHTML: (document.getElementById('chat')||{}).innerHTML ? 'yes':'no',
            charName: document.getElementById('char-chip-name').textContent,
            native: window.ZY && window.ZY.Tools && window.ZY.Tools.native.kind(),
        })""")
        print("STATE:", state)
        print("LOGS:")
        for l in logs[:30]: print("  ", l)
        await b.close()

asyncio.run(main())
