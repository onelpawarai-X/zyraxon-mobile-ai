"""Verify the built APK itself, not the folder it came from.

An APK is only useful if it installs and if it carries the *current* UI code —
the earlier bug was a stale/overwritten asset being packaged. So this opens the
zip, reads index.html and voice.js straight out of it, and checks that the
fixes are really inside the artifact the user will install.
"""
import re
import subprocess
import sys
import zipfile

APK = "/workspace/project/android/zyraxon.apk"
AAPT2 = "/home/openhands/android-sdk/build-tools/36.0.0/aapt2"
APKSIGNER = "/home/openhands/android-sdk/build-tools/36.0.0/apksigner"

fails = []

z = zipfile.ZipFile(APK)
names = z.namelist()


def read(n):
    return z.read(n).decode("utf-8", "replace")


# ── 1. the web app is inside the APK ────────────────────────────
for need in ["assets/index.html", "assets/js/voice.js", "assets/js/agent.js",
             "assets/js/native.js", "assets/knowledge/zyraxon-system-prompt.md"]:
    if need not in names:
        fails.append(f"missing from APK: {need}")
print(f"entries: {len(names)}")

# ── 2. index.html is the real app, not a generated website ──────
html = read("assets/index.html")
if "ZYRAXON" not in html.upper() or len(html) < 8000:
    fails.append(f"assets/index.html is not the ZYRAXON shell (len={len(html)})")
if "আমার সাইট" in html:
    fails.append("assets/index.html is a generated website, not the app")
print(f"index.html: {len(html)} bytes")

# ── 3. the current voice code is really packaged ────────────────
voice = read("assets/js/voice.js")
for marker in ["langOf", "resetLive", "stripCode", "_spokeAt"]:
    if marker not in voice:
        fails.append(f"voice.js in APK lacks {marker}")
if "1500" in voice:
    fails.append("voice.js in APK still has the old echo time-guard")

# ── 4. the Bangla-only rule reached the packaged prompt ─────────
# The rule is worded as "keep answering in Bangla" / "Never answer in English
# when the user spoke Bangla" — the older phrasing this test looked for no
# longer exists in the prompt.
prompt = read("assets/knowledge/zyraxon-system-prompt.md")
if "keep answering in Bangla" not in prompt or "Never answer in English when the user spoke Bangla" not in prompt:
    fails.append("packaged system prompt lacks the strict Bangla rule")

# ── 5. manifest: version, permissions, min SDK ──────────────────
man = subprocess.run([AAPT2, "dump", "badging", APK], capture_output=True, text=True).stdout
print("\n".join(l for l in man.splitlines() if l.startswith(("package", "sdkVersion", "targetSdkVersion", "uses-permission")))[:1200])
for perm in ["RECORD_AUDIO", "INTERNET", "SYSTEM_ALERT_WINDOW", "FOREGROUND_SERVICE",
             "QUERY_ALL_PACKAGES", "CALL_PHONE", "READ_CONTACTS", "WRITE_SETTINGS"]:
    if perm not in man:
        fails.append(f"permission missing: {perm}")

m = re.search(r"sdkVersion:'(\d+)'", man)
if m and int(m.group(1)) > 21:
    fails.append(f"minSdk {m.group(1)} would exclude Android 5 (must be ≤21)")

# ── 6. the package is signed (otherwise it cannot install) ──────
sig = subprocess.run([APKSIGNER, "verify", "--print-certs", APK], capture_output=True, text=True)
print(sig.stdout.strip()[:400])
if sig.returncode != 0 or "SHA-256" not in sig.stdout:
    fails.append("APK signature could not be verified")

print("FAILS:", fails if fails else "none")
sys.exit(1 if fails else 0)
