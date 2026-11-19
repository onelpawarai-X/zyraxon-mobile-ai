#!/usr/bin/env bash
#
# Check that the Linux payload's package repositories point at hosts that exist.
#
# The payload is a renamed copy of another distribution's. The rename once went
# as far as the repository URLs, so every mirror file read a host under a domain
# that does not resolve, and the repository directory was renamed too. The base
# system kept working — its packages are bundled — so the failure only showed up
# the first time a user asked to install something: `pkg` found no reachable
# mirror and refused, with no clue as to why.
#
# A name that does not resolve is worse than one that does, so this fails the
# build instead of the phone. Run it on the source tree before a release; pass an
# APK to check what actually shipped.
#
# usage: verify-package-sources.sh [apk]

set -uo pipefail

APK="${1:-}"

echo "== ZYRAXON package-source check =="

python3 - "$APK" <<'PY'
import io, os, re, socket, sys, zipfile

apk = sys.argv[1] if len(sys.argv) > 1 else ""
URL = re.compile(r"https?://([^/\s\"']+)")
# The repository directory is part of the URL, not only the host.
CHANNEL = re.compile(r"/(zyrxon-(?:main|root|x11))(?:[/\s\"']|$)")
BRANDED = re.compile(r"zyrxon", re.I)

# The repositories a fresh install actually uses. The rest of the list is a
# mirror catalogue: it carries mirrors upstream also lists as dead, and `pkg`
# skips those on its own, so a dead entry there is not a failure.
DEFAULTS = ("etc/apt/sources.list", "etc/zyrxon/mirrors/default")


def is_repo_file(name):
    if name.endswith("/"):
        return False
    return (
        name.startswith("etc/zyrxon/mirrors/")
        or name == "etc/apt/sources.list"
        or name.startswith("etc/apt/sources.list.d/")
    )


def resolve(host):
    try:
        socket.gethostbyname(host)
        return True
    except Exception:
        return False


def check(label, blob, problems):
    """blob is the bytes of a bootstrap.zip."""
    try:
        z = zipfile.ZipFile(io.BytesIO(blob))
    except Exception as e:
        problems.append(f"{label}: cannot read the payload archive: {e}")
        return 0
    names = [n for n in z.namelist() if is_repo_file(n)]
    if not names:
        problems.append(f"{label}: no repository files in the payload")
        return 0
    hosts, channels, defaults = set(), set(), set()
    for n in names:
        text = z.read(n).decode("utf-8", "replace")
        hosts |= set(URL.findall(text))
        channels |= set(CHANNEL.findall(text))
        if n in DEFAULTS:
            defaults |= set(URL.findall(text))

    # The regression this guards against: the brand leaked into a network name.
    branded = sorted(h for h in hosts if BRANDED.search(h))
    if branded:
        problems.append(f"{label}: {len(branded)} repository host(s) still branded: " + ", ".join(branded))
    if channels:
        problems.append(f"{label}: renamed repository path(s) still present: /" + ", /".join(sorted(channels)))
    dead = sorted(h for h in defaults if not resolve(h))
    if dead:
        problems.append(f"{label}: the default repository does not resolve: " + ", ".join(dead))
    if not branded and not channels and not dead:
        print(f"  \033[32mok\033[0m   {label}: {len(hosts)} host(s), default resolves, no branded names")
    return len(hosts)


problems = []
checked = 0

if apk:
    if not os.path.isfile(apk):
        print(f"  not a file: {apk}")
        sys.exit(2)
    with zipfile.ZipFile(apk) as z:
        nested = [n for n in z.namelist() if re.match(r"assets/linux/[^/]+/bootstrap\.zip$", n)]
        if not nested:
            print(f"  {os.path.basename(apk)}: no Linux payload found")
        for n in nested:
            checked += 1
            check(f"{os.path.basename(apk)} :: {n}", z.read(n), problems)
else:
    for abi, path in (
        ("arm64", "app/src/payload64/assets/linux/aarch64/bootstrap.zip"),
        ("arm32", "app/src/payload32/assets/linux/arm/bootstrap.zip"),
        ("x86_64", "app/src/payloadx64/assets/linux/x86_64/bootstrap.zip"),
    ):
        if not os.path.isfile(path):
            continue
        checked += 1
        check(abi, open(path, "rb").read(), problems)

for p in problems:
    print(f"  \033[31mFAIL\033[0m {p}")
if checked == 0:
    print("  no payload found to check")
    sys.exit(0)
if problems:
    sys.exit(1)
print("  package sources are reachable")
sys.exit(0)
PY
rc=$?

if [ "$rc" -eq 0 ]; then
  echo "== package sources ok =="
else
  echo "== package sources FAILED ==" >&2
fi
exit "$rc"
