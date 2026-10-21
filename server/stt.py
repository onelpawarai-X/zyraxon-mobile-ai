#!/usr/bin/env python3
"""Real speech-to-text for ZYRAXON, used when the browser has no Web Speech.

The provider's chat endpoint drops audio parts (verified: audio_tokens stays 0
for every input_audio shape), and Electron has no webkitSpeechRecognition, so
desktop and Firefox need a real recogniser. This runs Whisper locally.

Usage: stt.py <audio-file> [lang]   ->  prints JSON {text, lang, seconds}
"""
import json
import os
import sys


def decode_mono(path, rate=16000):
    """Decode any container to float32 mono PCM.

    faster-whisper's own decoder calls av.open(metadata_errors=...), which the
    installed PyAV build rejects, so decoding happens here instead.
    """
    import av
    import numpy as np

    with av.open(path) as container:
        stream = next((s for s in container.streams if s.type == "audio"), None)
        if stream is None:
            raise ValueError("no audio stream")
        resampler = av.AudioResampler(format="fltp", layout="mono", rate=rate)
        chunks = []
        for frame in container.decode(stream):
            for out in resampler.resample(frame):
                chunks.append(out.to_ndarray().reshape(-1))
        for out in resampler.resample(None):
            chunks.append(out.to_ndarray().reshape(-1))
    if not chunks:
        raise ValueError("empty audio")
    return np.concatenate(chunks).astype("float32")


def transcribe_file(path, lang, model_name):
    from faster_whisper import WhisperModel

    audio = decode_mono(path)
    model = WhisperModel(model_name, device="cpu", compute_type="int8")
    segments, info = model.transcribe(
        audio,
        language=lang,
        beam_size=1,
        vad_filter=True,
        condition_on_previous_text=False,
    )
    text = " ".join(s.text for s in segments).strip()
    return {
        "text": text,
        "lang": info.language,
        "confidence": round(float(info.language_probability), 3),
        "seconds": round(len(audio) / 16000.0, 2),
    }


def serve():
    """Stay alive and answer one JSON request per line.

    Loading the model takes ~40s, so a fresh process per utterance would be
    useless for a live conversation. This keeps it warm.
    """
    from faster_whisper import WhisperModel

    model_name = os.environ.get("ZYRAXON_STT_MODEL", "base")
    model = WhisperModel(model_name, device="cpu", compute_type="int8")
    sys.stdout.write(json.dumps({"ready": True, "model": model_name}) + "\n")
    sys.stdout.flush()

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except Exception:  # noqa: BLE001
            continue
        if req.get("stop"):
            break
        try:
            audio = decode_mono(req["path"])
            segments, info = model.transcribe(
                audio,
                language=(req.get("lang") or None),
                beam_size=1,
                vad_filter=True,
                condition_on_previous_text=False,
            )
            out = {
                "text": " ".join(s.text for s in segments).strip(),
                "lang": info.language,
                "confidence": round(float(info.language_probability), 3),
                "seconds": round(len(audio) / 16000.0, 2),
            }
        except Exception as exc:  # noqa: BLE001
            out = {"error": str(exc)}
        sys.stdout.write(json.dumps(out, ensure_ascii=False) + "\n")
        sys.stdout.flush()
    return 0


def main():
    if len(sys.argv) >= 2 and sys.argv[1] == "--serve":
        return serve()
    if len(sys.argv) < 2:
        print(json.dumps({"error": "audio path required"}))
        return 2
    path = sys.argv[1]
    lang = (sys.argv[2] or "").strip() or None
    model_name = os.environ.get("ZYRAXON_STT_MODEL", "base")

    try:
        result = transcribe_file(path, lang, model_name)
    except ImportError as exc:
        print(json.dumps({"error": f"faster-whisper unavailable: {exc}"}))
        return 3
    except Exception as exc:  # noqa: BLE001
        print(json.dumps({"error": str(exc)}))
        return 4
    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
