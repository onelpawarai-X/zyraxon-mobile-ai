#!/usr/bin/env python3
"""Repoint the bundled Linux payload's package repositories at hosts that exist.

The payload is a renamed copy of another distribution's. The rename reached the
repository URLs, so every mirror file under `etc/zyrxon/mirrors/` named a host
under a domain that does not resolve, and the repository directory was renamed
with it. Nothing else about the payload changed: the signing keys, the package
names and every user-visible path are the original ones, which is why the fix is
a URL rewrite and not a rebuild.

This rewrites the URL strings only, inside the existing archives, in place. Run
it once; `scripts/verify-package-sources.sh` proves the result.
"""

import os
import re
import sys
import zipfile

URL = re.compile(rb"https?://[^\s\"'<>\)\\]+")

# The renamed host is the upstream host with the brand swapped back. Applied to
# the host part of a URL, it is the same substitution the rename made, run
# backwards — which is why no per-host table is needed and no mirror is guessed.
BRANDS = ((b"zyrxon", b"termux"), (b"Zyrxon", b"Termux"))

# The files whose URLs are repository locations. Documentation and issue links
# are deliberately left alone: they are branding text, the app rewrites the
# welcome text itself, and a dead doc link costs nothing while a dead repository
# costs every install.
TARGETS = (
    "etc/apt/sources.list",
)


def is_target(name: str) -> bool:
    if name in TARGETS:
        return True
    if name.startswith("etc/apt/sources.list.d/"):
        return True
    if name.startswith("etc/zyrxon/mirrors/"):
        return not name.endswith("/")
    return False


def rebrand(url: bytes) -> bytes:
    out = url
    for wrong, right in BRANDS:
        out = out.replace(wrong, right)
    return out


def rewrite(data: bytes) -> bytes:
    return URL.sub(lambda m: rebrand(m.group(0)), data)


def patch(path: str) -> int:
    if not os.path.isfile(path):
        print(f"  missing: {path}")
        return 0
    with zipfile.ZipFile(path) as src:
        infos = src.infolist()
        changed = 0
        members = []
        for info in infos:
            data = src.read(info.filename)
            if is_target(info.filename):
                new = rewrite(data)
                if new != data:
                    changed += 1
                    data = new
            members.append((info, data))
    if not changed:
        print(f"  {path}: already clean")
        return 0
    tmp = path + ".tmp"
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as dst:
        for info, data in members:
            out = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            out.compress_type = info.compress_type
            out.external_attr = info.external_attr
            out.create_system = info.create_system
            dst.writestr(out, data)
    os.replace(tmp, path)
    print(f"  {path}: {changed} file(s) repointed")
    return changed


def main() -> int:
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    payloads = [
        "app/src/payload64/assets/linux/aarch64/bootstrap.zip",
        "app/src/payload32/assets/linux/arm/bootstrap.zip",
        "app/src/payloadx64/assets/linux/x86_64/bootstrap.zip",
    ]
    total = 0
    for rel in payloads:
        total += patch(os.path.join(root, rel))
    print(f"  {total} file(s) rewritten in total")
    return 0


if __name__ == "__main__":
    sys.exit(main())
