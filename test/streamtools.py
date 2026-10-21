import json, urllib.request

URL = "https://zyraxonai.lovable.app/api/public/zyraxon/v1/chat/completions"
UA = "Mozilla/5.0 (Linux; Android 14) Chrome/120 Mobile Safari/537.36"
T = [{"type": "function", "function": {"name": "publish_website", "description": "Publish a website",
      "parameters": {"type": "object", "properties": {"name": {"type": "string"}, "html": {"type": "string"}}, "required": ["name"]}}}]
U = "একটা ওয়েবসাইট বানিয়ে পাবলিশ করো।"

for model in ["zyraxon-titan", "zyraxon-pulse", "zyraxon-codex", "zyraxon-matrix"]:
    body = json.dumps({"model": model, "messages": [{"role": "user", "content": U}], "tools": T,
                       "tool_choice": "auto", "stream": True}).encode()
    req = urllib.request.Request(URL, data=body, headers={"Content-Type": "application/json", "User-Agent": UA})
    r = urllib.request.urlopen(req, timeout=200)
    got, fin, n, content = [], None, 0, ""
    for raw in r:
        line = raw.decode().strip()
        if not line.startswith("data:"):
            continue
        p = line[5:].strip()
        if p == "[DONE]":
            continue
        try:
            j = json.loads(p)
        except Exception:
            continue
        ch = j["choices"][0]
        d = ch.get("delta", {}) or {}
        if d.get("content"):
            content += d["content"]
        if d.get("tool_calls"):
            n += 1
            for tc in d["tool_calls"]:
                got.append((tc.get("function", {}).get("name"), (tc.get("function", {}).get("arguments") or "")[:50]))
        if ch.get("finish_reason"):
            fin = ch["finish_reason"]
    print(f"{model:18s} finish={fin} tool_deltas={n} calls={got[:3]} content={content[:80]!r}")
