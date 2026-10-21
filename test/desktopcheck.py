"""Verify the ZYRAXON desktop app really works, by driving the real Electron app.

Playwright's Electron API is Node-only and attaching a second CDP client to this
Electron build crashes the renderer, so the checks live inside the app itself
(desktop/lib/selftest.cjs) and this runner starts the app with ZYRAXON_SELFTEST=1
and reads its verdict. Every check exercises real code: a real shell, a real
screen capture, a real Bangla transcription, the real native bridge.

On a machine with no audio device the two microphone-loop checks are reported as
skipped by the app itself, not counted as failures — but they are exercised
whenever a device exists (pass --fake-mic to supply one).

Usage:  python3 test/desktopcheck.py [--fake-mic]
"""
import os
import pathlib
import re
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parent.parent
DESKTOP = ROOT / "desktop"
ELECTRON = DESKTOP / "node_modules" / "electron" / "dist" / "electron"
DISPLAY = os.environ.get("ZYRAXON_TEST_DISPLAY", ":99")
LOG = pathlib.Path("/tmp/zyraxon-desktopcheck.log")

LINE = re.compile(r"\[selftest\] (PASS|FAIL|SKIP) (.+?)(?: — (.*))?$")
DONE = re.compile(r"\[selftest\] (\d+) FAILED|ALL DESKTOP CHECKS PASSED")


def main():
    if not ELECTRON.exists():
        print(f"FAIL electron binary missing: {ELECTRON}")
        return 1

    fake_mic = "--fake-mic" in sys.argv
    args = [str(ELECTRON), ".", "--no-sandbox", "--disable-gpu", "--disable-dev-shm-usage"]
    if fake_mic:
        args += ["--use-fake-ui-for-media-stream", "--use-fake-device-for-media-stream"]

    env = {**os.environ, "DISPLAY": DISPLAY, "ZYRAXON_SELFTEST": "1", "ZYRAXON_STT_MODEL": "base"}
    print("starting the real Electron app (self-test mode)…", flush=True)
    with LOG.open("w") as log:
        proc = subprocess.Popen(args, cwd=str(DESKTOP), env=env, stdout=log, stderr=subprocess.STDOUT)

    passed, failed, skipped = [], [], []
    deadline = time.time() + 420
    try:
        while time.time() < deadline:
            text = LOG.read_text(errors="replace") if LOG.exists() else ""
            if DONE.search(text) or proc.poll() is not None:
                break
            time.sleep(2)
    finally:
        if proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                proc.kill()

    text = LOG.read_text(errors="replace") if LOG.exists() else ""
    for line in text.splitlines():
        m = LINE.search(line)
        if not m:
            continue
        kind, name, detail = m.group(1), m.group(2).strip(), (m.group(3) or "").strip()
        print(f"{kind:4} {name}" + (f" — {detail}" if detail else ""))
        (passed if kind == "PASS" else failed if kind == "FAIL" else skipped).append(name)

    if not passed and not failed:
        print("\nthe app produced no self-test output; last log lines:")
        print("\n".join(text.splitlines()[-15:]))
        return 1

    print(f"\n{len(passed)} passed, {len(failed)} failed, {len(skipped)} skipped")
    if failed:
        print("FAILED: " + ", ".join(failed))
        return 1
    print("ALL DESKTOP CHECKS PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
