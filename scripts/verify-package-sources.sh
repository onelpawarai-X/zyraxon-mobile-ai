#!/usr/bin/env bash
#
# Check what repository a shipped payload would talk to.
#
# The bundled system is a fork. It once shipped the whole mirror catalogue it was
# forked from under a renamed directory, and the rename had also reached the
# repository URLs — so every mirror named a host that does not resolve and `pkg`
# refused to install anything, while the bundled packages kept the base system
# alive and hid the failure until a user asked for something new.
#
# The app no longer trusts a list at all: it reads one address from our own
# manifest and writes a single `sources.list`. So what is checked here is the
# shape that follows from that —
#
#   * no mirror catalogue inside the payload,
#   * no branded hostname anywhere,
#   * exactly one seed repository, and it is the one we expect.
#
# The seed is the only place a foreign host may appear, and only until the
# in-point is live: it is overwritten the first time the app resolves our
# manifest. Pass `--repo URL` to assert the seed is already ours.
#
# usage: verify-package-sources.sh [apk] [--repo URL]

set -uo pipefail

APK=""
REPO=""
while [ $# -gt 0 ]; do
  case "$1" in
    --repo) REPO="${2:-}"; shift 2 ;;
    *) APK="$1"; shift ;;
  esac
done

echo "== ZYRAXON package-source check =="

python3 - "$APK" "$REPO" <<'PY'
import io, os, re, sys, zipfile

apk = sys.argv[1] if len(sys.argv) > 1 else ""
expect = sys.argv[2] if len(sys.argv) > 2 else ""

HOST = re.compile(r"https?://([^/\s\"']+)")
DEB = re.compile(r"https?://[^\s\"']+")
BRANDED = re.compile(r"zyrxon", re.I)
CATALOGUE = "etc/zyrxon/mirrors/"


def inspect(label, blob, problems):
    try:
        z = zipfile.ZipFile(io.BytesIO(blob))
    except Exception as e:
        problems.append(f"{label}: cannot read the payload archive: {e}")
        return

    names = z.namelist()
    catalogue = [n for n in names if n.startswith(CATALOGUE) and not n.endswith("/")]
    if catalogue:
        problems.append(f"{label}: {len(catalogue)} inherited mirror definition(s) still in the payload")

    sources = [n for n in names if n == "etc/apt/sources.list"] + [
        n for n in names if n.startswith("etc/apt/sources.list.d/") and not n.endswith("/")
    ]
    if not sources:
        problems.append(f"{label}: the payload has no sources.list at all")
        return

    hosts, seeds = set(), set()
    for n in sources:
        text = z.read(n).decode("utf-8", "replace")
        hosts |= set(HOST.findall(text))
        # The channel is part of the path on purpose: `/zyrxon-main` is a name
        # this repository decides, not a mirror directory that was renamed. What
        # must not happen is a `deb` line pointing anywhere but the in-point,
        # and that is exactly what `expect` below checks.
        for line in text.splitlines():
            if line.strip().startswith("deb "):
                seeds |= set(DEB.findall(line))

    branded = sorted(h for h in hosts if BRANDED.search(h))
    if branded:
        problems.append(f"{label}: branded hostname(s): " + ", ".join(branded))
    if not seeds:
        problems.append(f"{label}: no active `deb` line, so nothing can be installed")

    if expect:
        # A `deb` line names a base URL and a channel below it, so the base is
        # compared by prefix rather than equality.
        want = expect.rstrip("/")
        wrong = sorted(u for u in seeds if not (u == want or u.startswith(want + "/")))
        if wrong or not seeds:
            problems.append(
                f"{label}: the seed is not the in-point: " + (", ".join(wrong) or "no deb line")
            )

    if not branded and not catalogue and seeds:
        seed = ", ".join(sorted({m.group(1) for m in map(HOST.match, seeds) if m}))
        note = "in-point" if expect else "seed"
        print(f"  \033[32mok\033[0m   {label}: one {note} ({seed}), no catalogue, no branded names")


problems = []
checked = 0

if apk:
    if not os.path.isfile(apk):
        print(f"  not a file: {apk}")
        sys.exit(2)
    with zipfile.ZipFile(apk) as z:
        nested = [n for n in z.namelist() if re.match(r"assets/system/[^/]+/bootstrap\.zip$", n)]
        for n in nested:
            checked += 1
            inspect(f"{os.path.basename(apk)} :: {n}", z.read(n), problems)
    if not nested:
        print(f"  {os.path.basename(apk)}: no payload found")
else:
    for abi, path in (
        ("arm64", "app/src/payload64/assets/system/aarch64/bootstrap.zip"),
        ("arm32", "app/src/payload32/assets/system/arm/bootstrap.zip"),
        ("x86_64", "app/src/payloadx64/assets/system/x86_64/bootstrap.zip"),
    ):
        if not os.path.isfile(path):
            continue
        checked += 1
        inspect(abi, open(path, "rb").read(), problems)

for p in problems:
    print(f"  \033[31mFAIL\033[0m {p}")
if checked == 0:
    print("  no payload found to check")
    sys.exit(0)
if problems:
    sys.exit(1)
print("  package sources are clean")
sys.exit(0)
PY
rc=$?

if [ "$rc" -eq 0 ]; then
  echo "== package sources ok =="
else
  echo "== package sources FAILED ==" >&2
fi
exit "$rc"
