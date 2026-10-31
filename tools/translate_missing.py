#!/usr/bin/env python3
"""Add only the keys a language table is still missing.

`translate_ui.py` rebuilds every table from scratch, which is the right thing
when the key set changes a lot but costs a full re-translation. This script
keeps the existing translations and fills the gaps: it unions the UI literals
with the runtime display strings in `tools/runtime_keys.json`, then translates
just the keys absent from each table and merges them in.

Keys are the *evaluated* strings, so a `$name` interpolation is `{0}` here, the
same shape the Kotlin call site looks up.
"""

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import kotlin_strings as K  # noqa: E402
import translate_ui as T  # noqa: E402

ROOT = K.ROOT
OUT_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "i18n")
RUNTIME_FILE = os.path.join(ROOT, "tools", "runtime_keys.json")


def wanted_keys():
    keys = set()
    for raw in K.collect_keys():
        key, _ = T.tokenize(raw)
        keys.add(key)
    if os.path.exists(RUNTIME_FILE):
        for raw in json.load(open(RUNTIME_FILE, encoding="utf-8")):
            key, _ = T.tokenize(raw)
            keys.add(key)
    return sorted(keys)


def main():
    force = "--force" in sys.argv
    keys = wanted_keys()
    print("wanted keys:", len(keys))

    languages = json.load(open(T.LANGS_FILE, encoding="utf-8"))

    # Bengali is the identity map and the fallback, so it always carries every key.
    bn_path = os.path.join(OUT_DIR, "bn.json")
    bn = json.load(open(bn_path, encoding="utf-8")) if os.path.exists(bn_path) else {}
    for key in keys:
        bn.setdefault(key, key)
    json.dump(bn, open(bn_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1, sort_keys=True)

    failures = []
    for lang in languages:
        code = lang["code"]
        if code == "bn":
            continue
        path = os.path.join(OUT_DIR, code + ".json")
        table = json.load(open(path, encoding="utf-8")) if os.path.exists(path) else {}
        missing = [k for k in keys if k not in table] if not force else list(keys)
        if not missing:
            print("%-6s up to date" % code, flush=True)
            continue
        added = {}
        try:
            for start in range(0, len(missing), T.BATCH):
                chunk = missing[start : start + T.BATCH]
                protected = []
                names = []
                for text in chunk:
                    masked, found = T.protect(text)
                    protected.append(masked)
                    names.append(found)
                results = T.fetch(protected, code)
                for text, translated, found in zip(chunk, results, names):
                    value = T.restore(translated, found).strip()
                    if not value or any(value.count(name) != 1 for name in found):
                        value = text
                    added[text] = value
                import time

                time.sleep(0.6)
        except Exception as error:
            failures.append("%s: %s" % (code, error))
            print("%-6s FAILED %s" % (code, error), flush=True)
            continue
        table.update(added)
        json.dump(table, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=1, sort_keys=True)
        print("%-6s +%d (now %d)" % (code, len(added), len(table)), flush=True)

    if failures:
        print("failed: %s" % ", ".join(failures), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
