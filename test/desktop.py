"""Verify the new desktop/platform/tool layer end to end.

Everything here hits the real server and the real tool implementations:
platform detection, filesystem read/write/edit/list/glob/grep/delete,
web search, HTTP, sysinfo, and the shell running in the platform's own
shell. No mocks — the assertions read the real files back off disk.
"""
import asyncio
import os
import pathlib
import sys

from playwright.async_api import async_playwright

BASE = "http://localhost:12000"
ROOT = pathlib.Path(__file__).resolve().parent.parent


async def main():
    fails = []
    async with async_playwright() as p:
        b = await p.chromium.launch(args=["--no-sandbox"])
        pg = await (await b.new_context(viewport={"width": 412, "height": 915})).new_page()
        errs = []
        pg.on("pageerror", lambda e: errs.append(str(e)))
        await pg.add_init_script("localStorage.setItem('zyraxon:settings', JSON.stringify({tts:false,automic:false}));")
        await pg.goto(BASE, wait_until="networkidle")
        await pg.wait_for_timeout(1500)

        # ── 1. platform detection is real, not guessed ───────────
        plat = await pg.evaluate("()=>({p:window.ZY.Platform.data, ready:window.ZY.Platform.ready})")
        print("platform:", plat["p"]["kind"], plat["p"]["osName"], "host:", (plat["p"].get("host") or {}).get("osName"))
        if not plat["ready"]:
            fails.append("platform not ready")
        if not plat["p"].get("host"):
            fails.append("host probe missing")
        if plat["p"].get("host", {}).get("os") != sys.platform:
            fails.append(f"host os mismatch: {plat['p'].get('host',{}).get('os')} vs {sys.platform}")

        # the prompt must carry the platform block
        sysp = await pg.evaluate("async ()=>await window.ZY.Agent.buildSystem()")
        if "Where you are really running" not in sysp or "এখন আমি চলছি" not in sysp:
            fails.append("platform block missing from system prompt")
        if "শেল" not in sysp:
            fails.append("shell info missing from system prompt")

        # ── 2. tools exist and are callable ──────────────────────
        names = await pg.evaluate("()=>window.ZY.Tools.names()")
        want = ["read_file", "write_file", "edit_file", "list_dir", "find_files", "grep_files",
                "delete_file", "web_search", "http_request", "download_file", "sys_info",
                "platform_info", "process_list", "run_shell",
                "call_state", "call_answer", "call_hangup", "call_dial", "call_auto_answer"]
        missing = [w for w in want if w not in names]
        print(f"tools: {len(names)} total, missing {missing}")
        if missing:
            fails.append(f"missing tools {missing}")

        # every schema must be well-formed OpenAI function-call JSON
        api = await pg.evaluate("()=>window.ZY.Tools.apiTools()")
        for t in api:
            if t.get("type") != "function" or "name" not in t.get("function", {}):
                fails.append(f"bad tool schema {t}")
            if "parameters" not in t["function"]:
                fails.append(f"tool without parameters: {t['function'].get('name')}")

        async def call(name, args):
            return await pg.evaluate(
                "async ([n,a])=>await window.ZY.Tools.execute(n,a)", [name, args])

        # ── 3. filesystem: write -> read -> edit -> grep -> glob -> delete
        marker = "ZYRAXON_TEST_MARKER_42"
        r = await call("write_file", {"path": "data/tests/desktop_probe.txt", "content": f"line one\n{marker}\nline three\n"})
        print("write:", r["ok"], r["summary"][:70])
        if not r["ok"]:
            fails.append(f"write_file failed: {r}")
        disk = ROOT / "data/tests/desktop_probe.txt"
        if not disk.exists() or marker not in disk.read_text():
            fails.append("write_file did not really write to disk")

        r = await call("read_file", {"path": "data/tests/desktop_probe.txt"})
        print("read:", r["ok"], r["summary"][:70])
        if not r["ok"] or marker not in (r["data"].get("content") or ""):
            fails.append("read_file did not return the real content")

        r = await call("edit_file", {"path": "data/tests/desktop_probe.txt", "find": marker, "replace": "EDITED_OK"})
        print("edit:", r["ok"], r["summary"][:70])
        if not r["ok"] or "EDITED_OK" not in disk.read_text():
            fails.append("edit_file did not change the file on disk")

        r = await call("grep_files", {"path": "data/tests", "query": "EDITED_OK"})
        print("grep:", r["ok"], r["summary"][:70])
        if not r["ok"] or r["data"].get("count", 0) < 1:
            fails.append("grep_files found nothing")

        r = await call("find_files", {"path": "data", "pattern": "**/*.txt"})
        print("glob:", r["ok"], r["summary"][:70], r["data"].get("matches"))
        if not r["ok"] or not any("desktop_probe.txt" in m for m in r["data"].get("matches", [])):
            fails.append("find_files missed the file we just wrote")

        r = await call("list_dir", {"path": "data/tests"})
        if not r["ok"] or not any(e["name"] == "desktop_probe.txt" for e in r["data"]["entries"]):
            fails.append("list_dir did not list the file")

        # path escape must be refused
        r = await call("read_file", {"path": "../../../../etc/passwd"})
        print("escape refused:", not r["ok"], r["summary"][:60])
        if r["ok"]:
            fails.append("path traversal was not blocked")

        r = await call("delete_file", {"path": "data/tests/desktop_probe.txt"})
        print("delete:", r["ok"])
        if not r["ok"] or disk.exists():
            fails.append("delete_file did not remove the file")

        # ── 4. shell runs in the platform's own shell ────────────
        r = await call("run_shell", {"command": "echo zyraxon-shell-ok && uname -s"})
        print("shell:", r["ok"], r["data"].get("shell"), repr((r["data"].get("stdout") or "")[:60]))
        if not r["ok"] or "zyraxon-shell-ok" not in (r["data"].get("stdout") or ""):
            fails.append("run_shell produced no real stdout")
        if r["data"].get("shell") not in ("bash", "zsh", "sh", "fish"):
            fails.append(f"wrong shell picked: {r['data'].get('shell')}")

        # shell can really touch the disk
        r = await call("run_shell", {"command": "echo shell-wrote-this > data/tests/shell_probe.txt && cat data/tests/shell_probe.txt"})
        if "shell-wrote-this" not in (r["data"].get("stdout") or ""):
            fails.append("run_shell could not write a file")
        (ROOT / "data/tests/shell_probe.txt").unlink(missing_ok=True)

        # ── 5. web search returns real results ───────────────────
        r = await call("web_search", {"query": "capital of Bangladesh", "count": 5})
        print("search:", r["ok"], r["summary"][:110].replace("\n", " | "))
        if not r["ok"] or not r["data"].get("results"):
            fails.append("web_search returned nothing")
        elif not any("dhaka" in (x.get("title", "") + x.get("snippet", "")).lower()
                     for x in r["data"]["results"]):
            fails.append("web_search results look unrelated")

        # ── 6. http_request talks to a real API ──────────────────
        r = await call("http_request", {"url": "https://api.github.com/repos/nodejs/node", "method": "GET"})
        print("http:", r["ok"], r["summary"][:80])
        if not r["ok"] or not isinstance(r["data"].get("data"), dict):
            fails.append("http_request did not return parsed JSON")

        # ── 7. sys_info / process_list are real ──────────────────
        r = await call("sys_info", {})
        print("sysinfo:", r["summary"][:90])
        if not r["ok"] or r["data"].get("os") != sys.platform:
            fails.append("sys_info reported the wrong OS")
        if not r["data"].get("processes"):
            fails.append("sys_info returned no process list")

        r = await call("process_list", {})
        if not r["ok"] or not r["data"].get("processes"):
            fails.append("process_list returned nothing")

        r = await call("platform_info", {})
        print("platform_info:", r["summary"][:80])
        if not r["ok"] or "linux" not in r["summary"].lower() and "Linux" not in r["summary"]:
            fails.append("platform_info wrong")

        # ── 8. download_file really saves bytes ──────────────────
        r = await call("download_file", {"url": "https://raw.githubusercontent.com/nodejs/node/main/README.md", "path": "data/tests/dl.md"})
        print("download:", r["ok"], r["summary"][:80])
        if not r["ok"]:
            fails.append(f"download_file failed: {r['summary'][:80]}")
        else:
            (ROOT / "data/tests/dl.md").unlink(missing_ok=True)

        # ── 9. call tools degrade honestly in a browser ──────────
        r = await call("call_state", {})
        print("call_state in browser:", r["ok"], r["summary"][:60])
        if r["ok"]:
            fails.append("call_state claimed a native bridge in a plain browser")
        r = await call("call_dial", {"number": "+8801700000000"})
        if r["ok"]:
            fails.append("call_dial claimed success without the native bridge")

        if errs:
            fails.append(f"js errors {errs[:3]}")
        await b.close()

    print("FAILS:", fails if fails else "none")
    return 1 if fails else 0


sys.exit(asyncio.run(main()))
