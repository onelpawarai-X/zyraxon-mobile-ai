"""Find the audio content-part shape the provider actually understands.

`input_audio` came back as "no media attached", so the part was silently
dropped. Try the plausible shapes and keep whichever the model really hears.
"""
import base64
import json
import subprocess

BASE = "http://localhost:12000/api/chat"

# ~1s of a 440Hz tone as WAV (16-bit mono 8kHz) — audible if actually decoded.
import math
import struct

frames = b"".join(struct.pack("<h", int(12000 * math.sin(2 * math.pi * 440 * i / 8000))) for i in range(8000))
wav = (b"RIFF" + struct.pack("<I", 36 + len(frames)) + b"WAVEfmt " + struct.pack("<IHHIIHH", 16, 1, 1, 8000, 16000, 2, 16)
       + b"data" + struct.pack("<I", len(frames)) + frames)
b64 = base64.b64encode(wav).decode()
mp3 = base64.b64encode(b"ID3\x03\x00\x00\x00\x00\x00\x00" + b"\x00" * 200).decode()

VARIANTS = {
    "input_audio(base64+format)": {"type": "input_audio", "input_audio": {"data": b64, "format": "wav"}},
    "input_audio(data-url)": {"type": "input_audio", "input_audio": {"data": "data:audio/wav;base64," + b64, "format": "wav"}},
    "audio_url(data-url)": {"type": "audio_url", "audio_url": {"url": "data:audio/wav;base64," + b64}},
    "input_audio(mp3)": {"type": "input_audio", "input_audio": {"data": mp3, "format": "mp3"}},
    "file(data-url)": {"type": "file", "file": {"file_data": "data:audio/wav;base64," + b64}},
}


def ask(part):
    payload = {
        "model": "zyraxon-apex",
        "stream": False,
        "max_tokens": 150,
        "messages": [{"role": "user", "content": [
            {"type": "text", "text": "এই অডিওতে কী শুনছো? শুধু এক লাইনে বলো।"}, part]}],
    }
    out = subprocess.run(["curl", "-s", "-m", "120", "-X", "POST", BASE,
                          "-H", "Content-Type: application/json", "--data-binary", "@-"],
                         input=json.dumps(payload), capture_output=True, text=True).stdout
    try:
        # the proxy prefixes comment lines; find the JSON line
        i = out.index('{"id"')
        j, _ = json.JSONDecoder().raw_decode(out[i:])
        msg = j["choices"][0]["message"]
        return (msg.get("content") or ""), (msg.get("reasoning_content") or ""), None
    except Exception:
        return "", "", out[:300]


for name, part in VARIANTS.items():
    content, reasoning, err = ask(part)
    blob = (content + " " + reasoning).lower()
    dropped = "no media" in blob or "no image_url" in blob or "only text" in blob or "no audio" in blob
    print(f"--- {name} ---")
    print(("DROPPED  " if dropped else "ACCEPTED ") + (content or err or "").strip()[:220].replace("\n", " "))
    print()
