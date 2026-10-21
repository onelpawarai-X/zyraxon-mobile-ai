"""Prove tool calling works on EVERY ZYRAXON model — real API, no mocks.

For each model we ask a Bangla task that must trigger a tool call, using the
same `tools` payload the app sends, and report the tool the model chose.
"""
import json, urllib.request

BASE = "https://zyraxonai.lovable.app/api/public/zyraxon/v1"
UA = ("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")

TOOLS = [
    {"type": "function", "function": {
        "name": "open_app", "description": "মোবাইলে একটি অ্যাপ খুলো (যেমন facebook, chrome, whatsapp)",
        "parameters": {"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]}}},
    {"type": "function", "function": {
        "name": "read_url", "description": "একটি ওয়েবসাইট/ভিডিও পড়ে সারসংক্ষেপ দাও",
        "parameters": {"type": "object", "properties": {"url": {"type": "string"}}, "required": ["url"]}}},
    {"type": "function", "function": {
        "name": "todo_write", "description": "কাজের তালিকা তৈরি বা হালনাগাদ করো",
        "parameters": {"type": "object", "properties": {"items": {"type": "array", "items": {"type": "string"}}}, "required": ["items"]}}},
]

SYS = "তুমি ZYRAXON। ব্যবহারকারী যা বলবে সরাসরি টুল কল করে রিয়েলি করবে, শুধু কথা বলে থেমে যাবে না।"
TASK = "আমার ফেসবুক অ্যাপ খোলো।"


def post(path, payload, timeout=180):
    req = urllib.request.Request(BASE + path, data=json.dumps(payload).encode(),
                                 headers={"Content-Type": "application/json", "User-Agent": UA})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def get(path, timeout=120):
    req = urllib.request.Request(BASE + path, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


models = [m["id"] for m in get("/models")["data"]]
print(f"{len(models)} models found\n")
ok = 0
for mid in models:
    try:
        d = post("/chat/completions", {
            "model": mid,
            "messages": [{"role": "system", "content": SYS}, {"role": "user", "content": TASK}],
            "tools": TOOLS, "tool_choice": "auto", "stream": False,
        })
        c = d["choices"][0]
        tcs = c["message"].get("tool_calls") or []
        names = [t["function"]["name"] for t in tcs]
        if names:
            ok += 1
        shown = ",".join(names) if names else "—"
        print(f"{mid:26s} tools={shown:28s} finish={c.get('finish_reason')}")
    except Exception as e:
        print(f"{mid:26s} ERROR {type(e).__name__}: {str(e)[:70]}")

print(f"\n{ok}/{len(models)} models issued a real tool call")
