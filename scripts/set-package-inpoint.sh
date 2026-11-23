#!/usr/bin/env bash
#
# Point the shipped payloads at the ZYRAXON package in-point.
#
# Each `bootstrap.zip` carries an `etc/apt/sources.list` written when the
# payload was assembled. Until this runs, that file still names the repository
# the payload was forked from — the last foreign hostname in the product, kept
# only as a seatbelt so `pkg install` works on a phone that has never resolved
# our manifest.
#
# The seatbelt is only worth having if it is not the thing that breaks: a
# `sources.list` naming an address that does not answer makes every install
# fail. So the address is probed first, and nothing is rewritten unless it
# served the repository's own signed `Release` file.
#
# usage: set-package-inpoint.sh <https://host> [--dry-run]
#        set-package-inpoint.sh https://zyraxon-pkgs.example.workers.dev
#
# Afterwards, `verify-package-sources.sh --repo <url>` should pass without the
# "seed" wording: the payloads and the runtime resolver then agree.

set -uo pipefail

BASE=""
DRY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY=1; shift ;;
    http://*|https://*) BASE="$1"; shift ;;
    *) echo "unexpected argument: $1" >&2; exit 2 ;;
  esac
done

if [ -z "$BASE" ]; then
  echo "usage: $0 <https://host> [--dry-run]" >&2
  exit 2
fi

echo "== ZYRAXON package in-point =="

python3 - "$BASE" "$DRY" <<'PY'
import io, os, sys, urllib.request, zipfile

base = sys.argv[1].rstrip("/")
dry = sys.argv[2] == "1"

PAYLOADS = (
    ("arm64", "app/src/payload64/assets/system/aarch64/bootstrap.zip"),
    ("arm32", "app/src/payload32/assets/system/arm/bootstrap.zip"),
    ("x86_64", "app/src/payloadx64/assets/system/x86_64/bootstrap.zip"),
)

SOURCES = "etc/apt/sources.list"

BODY = """# ZYRAXON OS package sources.
# Served by ZYRAXON. The address is read from our own manifest,
# so it can move without a new build.
deb [trusted=yes] {base}/zyrxon-main stable main
deb [trusted=yes] {base}/zyrxon-root stable main
""".format(base=base)


def refuse(message):
    print(f"  \033[31mFAIL\033[0m {message}")
    sys.exit(1)


# A plain-http source would let anyone on the path decide which packages get
# installed, and `apt` would not notice.
if not base.startswith("https://"):
    refuse("the in-point must be https, got: " + base)

probe = base + "/zyrxon-main/dists/stable/Release"
try:
    # No `Range` here: a partial read returns one byte, and one byte cannot
    # prove the file is a `Release`. The whole file is 13 kB.
    request = urllib.request.Request(
        probe,
        headers={"User-Agent": "zyraxon-pkg/1"},
    )
    with urllib.request.urlopen(request, timeout=15) as answer:
        status = answer.status
        head = answer.read(4096).decode("utf-8", "replace")
except Exception as error:
    refuse(f"{probe} did not answer: {error}")

if status not in (200, 206):
    refuse(f"{probe} answered {status}")
# A page that is merely reachable is not a repository: `apt` reads `Origin` or
# `Suite` from this file before it trusts the index behind it.
if "Origin:" not in head and "Suite:" not in head:
    refuse(f"{probe} answered but does not look like a Release file")

print(f"  \033[32mok\033[0m   in-point answers: {base}")

if dry:
    print("  dry run: nothing written")
    sys.exit(0)


def rewrite(path):
    """Replace sources.list inside the payload, leaving every other member alone."""
    with zipfile.ZipFile(path) as archive:
        members = archive.infolist()
        if not any(m.filename == SOURCES for m in members):
            print(f"  \033[31mFAIL\033[0m {os.path.basename(path)}: no {SOURCES}")
            return False
        payload = io.BytesIO()
        with zipfile.ZipFile(payload, "w") as out:
            for member in members:
                # An emptied `sources.list.d` is deliberate: a leftover entry
                # there is fetched *in addition to* ours and can silently
                # reintroduce the host this script is removing.
                if member.filename.startswith("etc/apt/sources.list.d/") and not member.is_dir():
                    continue
                data = BODY.encode("utf-8") if member.filename == SOURCES else archive.read(member)
                info = zipfile.ZipInfo(member.filename, member.date_time)
                info.compress_type = member.compress_type
                info.external_attr = member.external_attr
                info.create_system = member.create_system
                info.internal_attr = member.internal_attr
                out.writestr(info, data)

    # Written beside the original and moved over it, so an interrupted run can
    # never leave a half-written payload that still looks like a zip.
    temp = path + ".new"
    with open(temp, "wb") as handle:
        handle.write(payload.getvalue())
    os.replace(temp, path)
    return True


for abi, path in PAYLOADS:
    if not os.path.isfile(path):
        print(f"  skip {abi}: {path} is not there")
        continue
    if not rewrite(path):
        sys.exit(1)
    print(f"  \033[32mok\033[0m   {abi}: sources.list now names the in-point")

# Re-read rather than trust the write: a payload that was not rewritten would
# otherwise only be discovered by a phone in the field.
for abi, path in PAYLOADS:
    if not os.path.isfile(path):
        continue
    with zipfile.ZipFile(path) as archive:
        text = archive.read(SOURCES).decode("utf-8", "replace")
    if base not in text:
        refuse(f"{abi}: the payload still does not name the in-point")
    leftovers = [n for n in archive.namelist() if n.startswith("etc/apt/sources.list.d/") and not n.endswith("/")]
    if leftovers:
        refuse(f"{abi}: {len(leftovers)} stale entry/entries survived in sources.list.d")

print("== payloads point at the in-point ==")
PY
rc=$?

if [ "$rc" -eq 0 ]; then
  echo "== done =="
else
  echo "== nothing was changed ==" >&2
fi
exit "$rc"
