#!/usr/bin/env python3
"""Strip the payload's inherited mirror catalogue.

The bundled system is a fork, and it shipped with the whole mirror catalogue it
was forked from: 71 files, each naming somebody else's server, under a directory
that had itself been renamed. Those files are not used by the app — the package
sources are written at runtime from our own manifest — and every one of them is
a foreign name sitting inside our APK.

So the fix is deletion rather than rewriting: nothing has to resolve, nothing has
to be kept in step with an upstream list, and the result is smaller.

Idempotent. Run it after a payload swap; `scripts/verify-package-sources.sh`
proves the result.
"""

import os
import sys
import zipfile

# Every directory that is a catalogue of other people's mirrors rather than a
# location this system uses.
CATALOGUES = (
    "etc/zyrxon/mirrors/",
)

PAYLOADS = (
    "app/src/payload64/assets/system/aarch64/bootstrap.zip",
    "app/src/payload32/assets/system/arm/bootstrap.zip",
    "app/src/payloadx64/assets/system/x86_64/bootstrap.zip",
)


def strip(path: str) -> int:
    if not os.path.isfile(path):
        print(f"  missing: {path}")
        return 0

    with zipfile.ZipFile(path) as src:
        keep = [
            (info, src.read(info.filename))
            for info in src.infolist()
            if not info.filename.startswith(CATALOGUES)
        ]
        dropped = len(src.infolist()) - len(keep)

    if not dropped:
        print(f"  {path}: already clean")
        return 0

    tmp = path + ".tmp"
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as dst:
        for info, data in keep:
            out = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            out.compress_type = info.compress_type
            out.external_attr = info.external_attr
            out.create_system = info.create_system
            dst.writestr(out, data)
    os.replace(tmp, path)
    print(f"  {path}: {dropped} catalogue entr(y/ies) removed")
    return dropped


def main() -> int:
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    total = 0
    for rel in PAYLOADS:
        total += strip(os.path.join(root, rel))
    print(f"  {total} entr(y/ies) removed in total")
    return 0


if __name__ == "__main__":
    sys.exit(main())
