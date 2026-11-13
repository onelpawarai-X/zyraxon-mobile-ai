#!/usr/bin/env bash
#
# Prove that a release APK has been hardened.
#
# The check answers one question: can someone who unzips the APK read the
# source tree out of it? It looks for the things that leak the architecture —
# class names, source-file names, the system prompt — and fails if any of them
# survive. Run it on a release APK; a debug build is expected to fail.
#
# Usage: scripts/verify-hardening.sh path/to/app-release.apk
set -uo pipefail

APK="${1:-}"
if [ -z "$APK" ] || [ ! -f "$APK" ]; then
  echo "usage: $0 path/to/app-release.apk" >&2
  exit 2
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

fail=0
say()  { printf '  %s\n' "$*"; }
bad()  { printf '  \033[31mFAIL\033[0m %s\n' "$*"; fail=1; }
good() { printf '  \033[32mok\033[0m   %s\n' "$*"; }

echo "== ZYRAXON hardening check: $(basename "$APK") =="

# An APK is a zip. `unzip` is present on the CI runner; a minimal sandbox may
# not have it, so reading the archive falls back to python's zipfile.
extract() {
  # extract <apk> <glob> <destdir>
  local apk="$1" glob="$2" dest="$3"
  if command -v unzip >/dev/null 2>&1; then
    unzip -qq -o "$apk" "$glob" -d "$dest" 2>/dev/null || true
  else
    python3 - "$apk" "$glob" "$dest" <<'PY' || true
import fnmatch, sys, zipfile
apk, glob, dest = sys.argv[1], sys.argv[2], sys.argv[3]
with zipfile.ZipFile(apk) as z:
    for n in z.namelist():
        if fnmatch.fnmatch(n, glob):
            z.extract(n, dest)
PY
  fi
}

# --- 1. Is the valuable source text readable? ---------------------------------
# The system prompt and the tool descriptions are the most valuable thing in
# the APK. Third-party licence texts are meant to be readable and are reported
# but not failed.
extract "$APK" 'assets/*' "$WORK"
valuable=$(find "$WORK/assets" -type f \( -path '*/knowledge/*' -o -path '*/i18n/*' \) \
  -not -path '*/sealed/*' \
  \( -name '*.txt' -o -name '*.md' -o -name '*.json' \) 2>/dev/null | wc -l)
# The sealed copies are ciphertext and carry no readable extension; a plain-text
# knowledge or i18n file is the leak. Anything under sealed/ is expected.
sealed_count=$(find "$WORK/assets/sealed" -type f 2>/dev/null | wc -l)
[ "$sealed_count" -gt 0 ] && good "$sealed_count sealed (encrypted) asset(s) present"

# The strongest proof: the prompt is known to contain this phrase. If it turns
# up in the shipped bytes the encryption did not happen, whatever the file names
# suggest.
if grep -rqa 'speech-to-speech' "$WORK/assets/sealed" 2>/dev/null; then
  bad "the prompt is still plain text inside the sealed assets"
else
  good "the sealed assets are ciphertext, not the prompt"
fi
if [ "$valuable" -gt 0 ]; then
  bad "$valuable readable asset(s) hold the system prompt or the tool text"
  find "$WORK/assets" -type f \( -path '*/knowledge/*' -o -path '*/i18n/*' \) -not -path '*/sealed/*' | sed 's/^/        /' | head -5
else
  good "no system-prompt or tool-text asset is readable"
fi
licences=$(find "$WORK/assets" -type f \( -name 'LICENSE*' -o -name 'CREDITS*' -o -name 'NOTICE*' \) 2>/dev/null | wc -l)
[ "$licences" -gt 0 ] && say "note: $licences licence text(s) readable, as they must be"

# --- 2. Do the class names survive in the dex? --------------------------------
# A handful of names must survive (Room, Moshi, the Android components), but
# the app's own architecture must not. These are ZYRAXON's own classes.
dexdir="$WORK/dex"; mkdir -p "$dexdir"
extract "$APK" 'classes*.dex' "$dexdir"
if ! ls "$dexdir"/*.dex >/dev/null 2>&1; then
  bad "no dex found in the APK"
else
  for name in ZyraxonAiEngine ToolRegistry AutomationPolicy ZyraxonViewModel NativeVoiceManager; do
    if grep -qa "$name" "$dexdir"/*.dex; then
      bad "class name '$name' survives in the dex — not obfuscated"
    else
      good "class name '$name' is gone"
    fi
  done
fi

# --- 3. Do the source-file names survive? -------------------------------------
# Two things leak the layout here. The source-file attribute can carry a real
# name, and the Compose compiler can bake `Name (File.kt:line)` strings into the
# dex. The second is the one that bit: class names were renamed but every
# composable still carried its file and line until the compose compiler and the
# blanket compose keep-rule were both dealt with. So the check is not a fixed
# list any more; it reads this project's own file names and fails on any of them,
# and separately fails on any trace-marker string at all.
SRC_DIR="${SRC_DIR:-app/src/main/java}"
grep -ohaE '[A-Za-z0-9_]+[.]kt:[0-9]+' "$dexdir"/*.dex 2>/dev/null |
  sed 's/:[0-9]*$//' | sort -u > "$WORK/kt_in_dex.txt"
find "$SRC_DIR" -name '*.kt' -printf '%f\n' 2>/dev/null | sort -u > "$WORK/kt_in_src.txt"

src_leak=0
if [ -s "$WORK/kt_in_src.txt" ]; then
  while read -r name; do
    [ -z "$name" ] && continue
    if grep -qxF "$name" "$WORK/kt_in_dex.txt"; then
      bad "source-file name '$name' survives — the layout of the code is readable"
      src_leak=1
    fi
  done < "$WORK/kt_in_src.txt"
else
  # No source tree to compare against (the script can be run against a bare APK),
  # so fall back to the names that matter most.
  for name in ZyraxonAiEngine.kt ToolRegistry.kt AutomationPolicy.kt NativeVoiceManager.kt; do
    if grep -qxF "$name" "$WORK/kt_in_dex.txt"; then
      bad "source-file name '$name' survives — the layout of the code is readable"
      src_leak=1
    fi
  done
fi
[ "$src_leak" -eq 0 ] && good "no project source-file name survives"

# An `<anonymous> (Some.kt:NNN)` string is a Compose trace marker. Even when it
# names a library file, the presence of one means the marker switch is back on.
if grep -qaE '<anonymous>[^)]*[.]kt:[0-9]+' "$dexdir"/*.dex 2>/dev/null; then
  bad "Compose trace markers survive — the file names and line numbers are readable"
else
  good "no Compose trace-marker string survives"
fi

# --- 4. Is a network security config attached? --------------------------------
# The manifest is compiled to binary XML, so its strings are not plain text.
# aapt2 reads it properly; fall back to a plain grep only if aapt2 is absent.
aapt2_bin="${AAPT2:-$(command -v aapt2 || true)}"
if [ -z "$aapt2_bin" ] && [ -n "${ANDROID_HOME:-}" ]; then
  aapt2_bin="$(ls -d "$ANDROID_HOME"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1)"
fi
if [ -n "$aapt2_bin" ] && [ -x "$aapt2_bin" ]; then
  dump="$("$aapt2_bin" dump xmltree --file AndroidManifest.xml "$APK" 2>/dev/null || true)"
  if printf '%s' "$dump" | grep -q 'networkSecurityConfig'; then
    good "a network security config is attached"
  else
    bad "no network security config on <application>"
  fi
  if printf '%s' "$dump" | grep -q 'android:allowBackup.*false'; then
    good "allowBackup is false"
  else
    bad "allowBackup is not false"
  fi
  if printf '%s' "$dump" | grep -q 'usesCleartextTraffic.*false'; then
    good "usesCleartextTraffic is false"
  else
    bad "usesCleartextTraffic is not false"
  fi
else
  say "note: aapt2 not found, skipping the manifest checks"
fi

echo
if [ "$fail" -eq 0 ]; then
  echo "== hardened: nothing readable was found =="
else
  echo "== NOT hardened: see the failures above =="
fi
exit "$fail"
