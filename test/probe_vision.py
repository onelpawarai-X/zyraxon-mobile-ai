import base64, json, urllib.request

def ask(payload, tag):
    req = urllib.request.Request(
        "https://zyraxonai.lovable.app/api/public/zyraxon/v1/chat/completions",
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json",
                 "User-Agent": "Mozilla/5.0 (Linux; Android 14; Pixel 8) Chrome/120 Mobile Safari/537.36"})
    try:
        r = urllib.request.urlopen(req, timeout=150)
        j = json.loads(r.read())
        print(tag, "->", j["choices"][0]["message"].get("content"))
    except Exception as e:
        print(tag, "ERR", e, getattr(e, "read", lambda: b"")()[:300])

png = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")
ask({"model": "zyraxon-cyber", "messages": [{"role": "user", "content": [
    {"type": "text", "text": "এই ছবিতে কী রঙ আছে? এক শব্দে বলো।"},
    {"type": "image_url", "image_url": {"url": "data:image/png;base64," + base64.b64encode(png).decode()}}]}],
    "stream": False}, "VISION-cyber")

ask({"model": "zyraxon-visionary", "messages": [{"role": "user", "content": [
    {"type": "text", "text": "এই ছবিতে কী রঙ আছে? এক শব্দে বলো।"},
    {"type": "image_url", "image_url": {"url": "data:image/png;base64," + base64.b64encode(png).decode()}}]}],
    "stream": False}, "VISION-visionary")
