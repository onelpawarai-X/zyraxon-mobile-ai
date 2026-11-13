#!/usr/bin/env bash
#
# Check that an APK carries the CPU family and the Linux payload it claims.
#
# The release builds three files: arm64, arm32 and universal. A wrong abiFilters
# case or a wrong payload source set would still produce an APK, and the phone
# would only find out at install time — or worse, install and then fail to start
# the bundled Linux. This reads the APK's own member list and says what is
# inside, so a mistake fails the build instead of the phone.
#
# usage: verify-apk-abi.sh <apk> <arm64|arm32|universal>

set -uo pipefail

if [ $# -ne 2 ]; then
  echo "usage: $0 <apk> <arm64|arm32|universal>" >&2
  exit 2
fi

APK="$1"
ABI="$2"

if [ ! -f "$APK" ]; then
  echo "not a file: $APK" >&2
  exit 1
fi

listing="$(python3 - "$APK" <<'PY'
import sys, zipfile
try:
    with zipfile.ZipFile(sys.argv[1]) as z:
        print("\n".join(z.namelist()))
except Exception as exc:
    sys.stderr.write(f"{exc}\n")
    sys.exit(1)
PY
)"
if [ -z "$listing" ]; then
  echo "cannot read the member list of $APK" >&2
  exit 1
fi

libs="$(printf '%s\n' "$listing" | grep -oE '^lib/[^/]+/' | sort -u)"
payloads="$(printf '%s\n' "$listing" | grep -oE '^assets/linux/[^/]+/' | sort -u)"

fail=0

has() {
  if printf '%s\n' "$1" | grep -qxF "$2"; then
    printf '  ok    %s\n' "$3"
  else
    printf '  MISS  %s\n' "$3"
    fail=1
  fi
}

lacks() {
  if printf '%s\n' "$1" | grep -qxF "$2"; then
    printf '  BAD   %s\n' "$3"
    fail=1
  else
    printf '  ok    %s\n' "$3"
  fi
}

echo "$(basename "$APK") — claiming $ABI"

case "$ABI" in
  arm64)
    has   "$libs" "lib/arm64-v8a/"            "arm64-v8a native libraries"
    lacks "$libs" "lib/armeabi-v7a/"          "no armeabi-v7a libraries"
    has   "$payloads" "assets/linux/aarch64/" "aarch64 Linux payload"
    lacks "$payloads" "assets/linux/arm/"     "no arm Linux payload"
    ;;
  arm32)
    has   "$libs" "lib/armeabi-v7a/"          "armeabi-v7a native libraries"
    lacks "$libs" "lib/arm64-v8a/"            "no arm64-v8a libraries"
    has   "$payloads" "assets/linux/arm/"     "arm Linux payload"
    lacks "$payloads" "assets/linux/aarch64/" "no aarch64 Linux payload"
    ;;
  universal)
    has "$libs" "lib/arm64-v8a/"              "arm64-v8a native libraries"
    has "$libs" "lib/armeabi-v7a/"            "armeabi-v7a native libraries"
    has "$payloads" "assets/linux/aarch64/"   "aarch64 Linux payload"
    has "$payloads" "assets/linux/arm/"       "arm Linux payload"
    ;;
  *)
    echo "unknown variant: $ABI (want arm64, arm32 or universal)" >&2
    exit 2
    ;;
esac

# No phone runs the desktop x86 libraries or the x86_64 payload, so no variant
# may carry them — the universal build is the one that could pick them up by
# accident, because it is the one that adds more than a single source set.
lacks "$libs" "lib/x86/"                  "no x86 libraries"
lacks "$libs" "lib/x86_64/"               "no x86_64 libraries"
lacks "$payloads" "assets/linux/x86_64/"  "no x86_64 Linux payload"

echo "  ---"
echo "  native libraries: $(printf '%s' "$libs" | tr '\n' ' ')"
echo "  Linux payloads:   $(printf '%s' "$payloads" | tr '\n' ' ')"

if [ "$fail" -ne 0 ]; then
  echo "FAILED — $ABI"
  exit 1
fi

echo "PASSED — $ABI"
