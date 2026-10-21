"""Does the provider actually accept video / audio / image parts?

The UI can build a perfect message and still fail if the upstream model rejects
the content-part shapes. This asks the real provider directly with a tiny real
file of each kind and reports the HTTP status and any error text.
"""
import base64
import json
import pathlib
import subprocess

BASE = "http://localhost:12000/api/chat"
ROOT = pathlib.Path("/workspace/project")


def part_try(label, part, extra_text="এইটা কী দেখতে পাচ্ছো?"):
    # 1x1 PNG, a tiny silent WAV, and a real mp4 from the app's own assets.
    payload = {
        "model": "zyraxon-apex",
        "stream": False,
        "max_tokens": 120,
        "messages": [{"role": "user", "content": [{"type": "text", "text": extra_text}, part]}],
    }
    out = subprocess.run(
        ["curl", "-s", "-m", "120", "-X", "POST", BASE,
         "-H", "Content-Type: application/json", "--data-binary", "@-"],
        input=json.dumps(payload), capture_output=True, text=True).stdout
    print(f"--- {label} ---")
    print(out[:600].replace("\n", " "))
    print()
    return out


png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg=="
mp4 = base64.b64encode((ROOT / "public/assets/bg/city.mp4").read_bytes()).decode()
wav = base64.b64encode(
    b"RIFF$\x00\x00\x00WAVEfmt \x10\x00\x00\x00\x01\x00\x01\x00\x40\x1f\x00\x00"
    b"\x80>\x00\x00\x02\x00\x10\x00data\x00\x00\x00\x00").decode()

results = {}
results["image"] = part_try("image_url", {"type": "image_url", "image_url": {"url": "data:image/png;base64," + png}})
results["video"] = part_try("video_url", {"type": "video_url", "video_url": {"url": "data:video/mp4;base64," + mp4}})
results["audio"] = part_try("input_audio", {"type": "input_audio", "input_audio": {"data": "data:audio/wav;base64," + wav, "format": "wav"}})

fails = []
for kind, raw in results.items():
    ok = '"content"' in raw or '"reasoning_content"' in raw
    err = "error" in raw.lower() and not ok
    print(f"{kind}: accepted={ok} error={err}")
    if not ok:
        fails.append(f"{kind} part rejected by the provider: {raw[:200]}")

print("FAILS:", fails if fails else "none")
