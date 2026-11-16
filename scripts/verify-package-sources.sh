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
import os, re, socket, sys, zipfile

apk = sys.argv[1] if len(sys.argv) > 1 else ""
payloads = []
if apk:
    if not os.path.isfile(apk):
        print(f"  not a file: {apk}")
        sys.exit(2)
    payloads.append(("APK", apk))
else:
    for abi, path in (
        ("arm64", "app/src/payload64/assets/linux/aarch64/bootstrap.zip"),
        ("arm32", "app/src/payload32/assets/linux/arm/bootstrap.zip"),
        ("x86_64", "app/src/payloadx64/assets/linux/x86_64/bootstrap.zip"),
    ):
        if os.path.isfile(path):
            payloads.append((abi, path))

if not payloads:
    print("  no payload found to check")
    sys.exit(0)

URL = re.compile(r"https?://([^/\s\"']+)")
# The repository directory is part of the URL, not only the host.
CHANNEL = re.compile(r"/(zyrxon-(?:main|root|x11))(?:[/\s\"']|$)")

def resolve(host):
    try:
        socket.gethostbyname(host)
        return True
    except Exception:
        return False

fail = 0
for label, path in payloads:
    with zipfile.ZipFile(path) as z:
        names = [
            n for n in z.namelist()
            if (n.startswith("etc/zyrxon/mirrors/") and not n.endswith("/"))
            or n in ("etc/apt/sources.list", "etc/zyrxon/mirrors/default")
            or n.startswith("etc/apt/sources.list.d/")
        ]
        if not names:
            print(f"  {label}: no repository files found")
            continue
        hosts, channels, default_hosts = set(), set(), set()
        for n in names:
            try:
                text = z.read(n).decode("utf-8", "replace")
            except Exception:
                continue
            found = set(URL.findall(text))
            hosts |= found
            channels |= set(CHANNEL.findall(text))
            if n in ("etc/apt/sources.list", "etc/zyrxon/mirrors/default"):
                default_hosts |= found

    # The regression this guards against: the brand leaked into a network name.
    # A name under the old domain never resolved, and a mirror that answers but
    # has no such path is still a dead repository.
    branded = sorted(h for h in hosts if "zyrxon" in h.lower())
    if branded:
        fail = 1
        print(f"  \033[31mFAIL\033[0m {label}: {len(branded)} repository host(s) still branded:")
        for h in branded:
            print(f"         {h}")
    if channels:
        fail = 1
        print(f"  \033[31mFAIL\033[0m {label}: renamed repository path(s) still present:")
        for c in sorted(channels):
            print(f"         /{c}  (the upstream path is /termux-...)")

    # The repositories a fresh install actually uses must answer. The rest of the
    # list is a mirror catalogue: it carries mirrors upstream also lists as dead,
    # and `pkg` skips those on its own, so a dead entry there is not a failure.
    dead_default = sorted(h for h in default_hosts if not resolve(h))
    if dead_default:
        fail = 1
        print(f"  \033[31mFAIL\033[0m {label}: the default repository does not resolve:")
        for h in dead_default:
            print(f"         {h}")

    if not branded and not channels and not dead_default:
        print(f"  \033[32mok\033[0m   {label}: {len(hosts)} host(s), default resolves, no branded names")

if fail == 0:
    print("  package sources are reachable")
sys.exit(fail)
PY
rc=$?

if [ "$rc" -eq 0 ]; then
  echo "== package sources ok =="
else
  echo "== package sources FAILED ==" >&2
fi
exit "$rc"
