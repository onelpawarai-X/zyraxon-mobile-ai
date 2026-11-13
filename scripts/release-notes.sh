#!/usr/bin/env bash
#
# Write the release notes for a version, taken from VERSIONS.md.
#
# The release body used to be the tag annotation, which is one line, so every
# release looked empty. The real notes live in VERSIONS.md under a heading of the
# exact form "## vX.Y.Z", and this pulls that section out verbatim.
#
# Usage: scripts/release-notes.sh 6.25.0 [output-file]
#        scripts/release-notes.sh v6.25.0
#
# Exits non-zero when VERSIONS.md has no section for the version, so a release
# can never be published with an empty body by accident.
set -uo pipefail

VERSION="${1:-}"
OUT="${2:-}"

if [ -z "$VERSION" ]; then
  echo "usage: $0 <version> [output-file]" >&2
  exit 2
fi

# Accept both 6.25.0 and v6.25.0.
TAG="v${VERSION#v}"
FILE="${VERSIONS_FILE:-VERSIONS.md}"

if [ ! -f "$FILE" ]; then
  echo "release-notes: $FILE not found" >&2
  exit 1
fi

# Take everything from "## $TAG" up to the next "## " heading. awk rather than
# sed so the section boundary is a heading at the start of a line and nothing
# else can match it.
NOTES="$(awk -v tag="$TAG" '
  $0 ~ "^## " tag "[[:space:]]*$" { found = 1; next }
  found && /^## / { exit }
  # Skip the metadata line; the release body is the notes only.
  found && /^\*\*Date:\*\*/ { next }
  found && /^### Release notes[[:space:]]*$/ { next }
  found { print }
' "$FILE")"

# Trim leading and trailing blank lines without touching the inner spacing.
NOTES="$(printf '%s\n' "$NOTES" | sed -e '/./,$!d' | sed -e ':a' -e '/^\n*$/{$d;N;ba' -e '}')"

if [ -z "$NOTES" ]; then
  echo "release-notes: $FILE has no '## $TAG' section — add one before releasing" >&2
  exit 1
fi

if [ -n "$OUT" ]; then
  printf '%s\n' "$NOTES" > "$OUT"
  echo "release-notes: wrote $(printf '%s\n' "$NOTES" | wc -l) lines for $TAG to $OUT"
else
  printf '%s\n' "$NOTES"
fi
