#!/usr/bin/env bash
#
# Prove that the version in app/build.gradle.kts and the version ledger in
# VERSIONS.md agree, and that the ledger has a real entry for the version.
#
# The failure this guards against is a release that ships a number nobody wrote
# down: VERSIONS.md would then describe a different version than the APK carries,
# the release notes would be empty, and the next version would be chosen from a
# stale table. This runs in CI before any build, so the mismatch is caught on the
# commit rather than on the tag.
#
# Usage: scripts/verify-version.sh
set -uo pipefail

GRADLE_FILE="app/build.gradle.kts"
LEDGER="VERSIONS.md"

fail() {
  echo "verify-version: $1" >&2
  exit 1
}

[ -f "$GRADLE_FILE" ] || fail "$GRADLE_FILE not found"
[ -f "$LEDGER" ] || fail "$LEDGER not found"

NAME="$(grep -oP 'versionName\s*=\s*"\K[^"]+' "$GRADLE_FILE" | head -1)"
CODE="$(grep -oP 'versionCode\s*=\s*\K[0-9]+' "$GRADLE_FILE" | head -1)"

[ -n "$NAME" ] || fail "could not read versionName from $GRADLE_FILE"
[ -n "$CODE" ] || fail "could not read versionCode from $GRADLE_FILE"

# A plain X.Y.Z, with no suffix. A "-test1" style name must never reach a tag.
if ! printf '%s' "$NAME" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$'; then
  fail "versionName '$NAME' is not a plain X.Y.Z — release names carry no suffix"
fi

# The ledger must carry the same version as a table row and as a section.
if ! grep -qF "\`$NAME\`" "$LEDGER"; then
  fail "$LEDGER has no table row for version $NAME"
fi

if ! grep -qE "^## v$NAME[[:space:]]*$" "$LEDGER"; then
  fail "$LEDGER has no '## v$NAME' section — the release notes would be empty"
fi

# The section's own versionCode, when it states one, must match the build.
SECTION_CODE="$(awk -v tag="v$NAME" '
  $0 ~ "^## " tag "[[:space:]]*$" { found = 1; next }
  found && /^## / { exit }
  found && /versionCode/ { print; exit }
' "$LEDGER" | grep -oP 'versionCode:\*{0,2}\s*\K[0-9]+' | head -1)"

if [ -n "$SECTION_CODE" ] && [ "$SECTION_CODE" != "$CODE" ]; then
  fail "$LEDGER says versionCode $SECTION_CODE for $NAME, but $GRADLE_FILE says $CODE"
fi

echo "verify-version: $NAME (versionCode $CODE) is written down in $LEDGER"
