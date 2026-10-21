"""Prove GitHub really works: commit files to the connected repository, then
run a real Windows / Linux / macOS GitHub Actions build and report the true
conclusion of every job and every artifact.
"""
import json, urllib.request

BASE = "http://127.0.0.1:12000"


def post(path, body):
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(req, timeout=900))


def get(path):
    return json.load(urllib.request.urlopen(BASE + path, timeout=60))


info = get("/api/github/info")
print("1. account:", info["login"], "| repos:", [r["name"] for r in info["repos"]])
repo = info["repos"][0]["url"].replace("https://github.com/", "")
print("   target repo:", repo)

pub = post("/api/github/publish", {
    "name": "zyraxon-desktop",
    "repo": repo,
    "pages": True,
    "files": {
        "index.html": "<!doctype html><html><head><meta charset='utf-8'>"
                      "<title>ZYRAXON</title></head><body>"
                      "<h1>Published by ZYRAXON</h1></body></html>",
        "app.py": "import tkinter as tk\n\n\ndef main():\n"
                  "    root = tk.Tk()\n    root.title('ZYRAXON desktop')\n"
                  "    tk.Label(root, text='Hello from ZYRAXON').pack(padx=40, pady=20)\n"
                  "    root.after(1200, root.destroy)\n    root.mainloop()\n\n\n"
                  "if __name__ == '__main__':\n    main()\n",
    },
})
print("2. publish ok:", pub["ok"], "| folder:", pub["folder"])
print("   committed:", pub["committed"])
if pub.get("failed"):
    print("   FAILED:", pub["failed"])
print("   pages:", pub.get("pages"))

matrix = """name: build
on:
  workflow_dispatch:
jobs:
  build:
    strategy:
      fail-fast: false
      matrix:
        os: [ubuntu-latest, windows-latest, macos-latest]
    runs-on: ${{ matrix.os }}
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with:
          python-version: '3.12'
      - name: Install
        run: python -m pip install --upgrade pip pyinstaller
      - name: Build
        run: pyinstaller --onefile --name zyraxon-app app.py
      - name: Show the build
        shell: bash
        run: ls -la dist
      - uses: actions/upload-artifact@v4
        with:
          name: zyraxon-app-${{ matrix.os }}
          path: dist/*
"""
wf = post("/api/github/workflow", {"repo": repo, "content": matrix})
print("3. workflow:", wf)

print("4. dispatching the three-OS build (several minutes)…")
res = post("/api/github/actions", {"repo": repo, "workflow": "build.yml", "timeout_ms": 900000})
print("   ok:", res.get("ok"), "| conclusion:", res.get("conclusion"), "| stage:", res.get("stage"))
for j in res.get("jobs", []):
    print(f"   job {j['name']}: {j['conclusion']}")
    for s in j.get("steps", []):
        if s["conclusion"] not in ("success", "skipped"):
            print(f"      step {s['name']}: {s['conclusion']}")
print("   artifacts:", [a["name"] for a in res.get("artifacts", [])])
print("   run:", res.get("runUrl"))
