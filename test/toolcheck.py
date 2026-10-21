import json, urllib.request, sys

PROXY = "http://localhost:12000/api/chat"
SYS = "তুমি ZYRAXON, বাংলাভাষী মোবাইল সহকারী। ব্যবহারকারী যা বলবে, টুল কল করে রিয়েলি করবে। খালি কথা বলে থেমে যাবে না। উত্তর সবসময় বাংলায়।"

def tools():
    def fn(name, desc, props, req):
        return {"type": "function", "function": {"name": name, "description": desc,
                "parameters": {"type": "object", "properties": props, "required": req}}}
    return [
        fn("todo_write", "লাইভ টাস্ক বোর্ড আপডেট করো", {"todos": {"type": "array", "items": {"type": "object", "properties": {"id": {"type": "string"}, "title": {"type": "string"}, "status": {"type": "string"}}}}}, ["todos"]),
        fn("generate_file", "ফাইল লেখো", {"path": {"type": "string"}, "content": {"type": "string"}}, ["path", "content"]),
        fn("publish_website", "ওয়েবসাইট পাবলিশ করো", {"name": {"type": "string"}, "html": {"type": "string"}}, ["name"]),
        fn("read_screen", "স্ক্রিন দেখো", {}, []),
        fn("open_app", "অ্যাপ খোলো", {"name": {"type": "string"}, "package": {"type": "string"}}, []),
    ]

def ask(model, user):
    body = json.dumps({"model": model, "stream": False, "tools": tools(), "tool_choice": "auto",
                       "messages": [{"role": "system", "content": SYS}, {"role": "user", "content": user}]}).encode()
    req = urllib.request.Request(PROXY, data=body, headers={"Content-Type": "application/json",
                                   "User-Agent": "Mozilla/5.0 (Linux; Android 14) Chrome/120 Mobile Safari/537.36"})
    with urllib.request.urlopen(req, timeout=180) as r:
        raw = r.read().decode()
    # proxy returns SSE even for stream:false; parse last data chunk
    obj = None
    for line in raw.split("\n"):
        line = line.strip()
        if line.startswith("data:") and "[DONE]" not in line:
            p = line[5:].strip()
            if p:
                try: obj = json.loads(p)
                except: pass
    if not obj:
        return {"raw": raw[:400]}
    c = obj["choices"][0]
    return {"finish": c.get("finish_reason"), "content": (c["message"].get("content") or "")[:200],
            "tools": [(t["function"]["name"], t["function"]["arguments"]) for t in (c["message"].get("tool_calls") or [])]}

for m in ["zyraxon-titan", "zyraxon-pulse", "zyraxon-codex"]:
    print("=== ", m)
    print(json.dumps(ask(m, "একটা ছোট ওয়েবসাইট বানিয়ে পাবলিশ করো যেখানে লেখা থাকবে হ্যালো জাইরাক্সন।"), ensure_ascii=False)[:900])
