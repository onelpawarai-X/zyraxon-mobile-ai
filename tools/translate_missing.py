#!/usr/bin/env python3
"""Add the keys a language table is still missing, and repair the untranslated ones.

`translate_ui.py` rebuilds every table from scratch, which is the right thing
when the key set changes a lot but costs a full re-translation. This script
keeps the existing translations and fills the gaps: it unions the UI literals
with the runtime display strings in `tools/runtime_keys.json`, then translates
just the keys absent from each table and merges them in.

A key that is *present* but still holds the Bengali source is treated as
missing too. That is what a failed request leaves behind — the caller falls back
to the source so nothing is blank — and without this the failure would be
permanent, because the entry looks translated to a check that only asks whether
the key exists. Assamese is the one language the endpoint will not answer for,
so its table keeps whatever it already has rather than being blanked.

Keys are the *evaluated* strings, so a `$name` interpolation is `{0}` here, the
same shape the Kotlin call site looks up.
"""

import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import kotlin_strings as K  # noqa: E402
import translate_ui as T  # noqa: E402

ROOT = K.ROOT
OUT_DIR = os.path.join(ROOT, "assets-src", "i18n")
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
    # English is the reference table: a key present there but absent from another
    # language is filled too, even when the current scan of the Kotlin does not
    # produce it.
    en_path = os.path.join(OUT_DIR, "en.json")
    if os.path.exists(en_path):
        keys |= set(json.load(open(en_path, encoding="utf-8")))
    return sorted(keys)


def tool_keys():
    """The tool-message fragments, keyed by the English table.

    These are translated by fragment and substituted into a Bengali summary at
    runtime, so they are a table of their own: `tools-en.json` is the reference
    and every other `tools-*.json` is filled from it. English is complete, so
    the runtime fallback never leaves Bengali behind, but a language with its own
    entry is the one a reader actually gets.
    """
    ref = os.path.join(OUT_DIR, "tools-en.json")
    if not os.path.exists(ref):
        return []
    return sorted(json.load(open(ref, encoding="utf-8")))


def translate_table(path, missing, code):
    """Translates [missing] into [code] and merges them into the table at [path]."""
    table = json.load(open(path, encoding="utf-8")) if os.path.exists(path) else {}
    added = {}
    for start in range(0, len(missing), T.BATCH):
        chunk = missing[start : start + T.BATCH]
        protected = []
        names = []
        for text in chunk:
            masked, found = T.protect(T.guard_brand(text))
            protected.append(masked)
            names.append(found)
        results = T.fetch(protected, code)
        for text, translated, found in zip(chunk, results, names):
            value = T.restore_brand(T.restore(translated, found)).strip()
            if not value or any(value.count(name) != 1 for name in found):
                value = text
            # The endpoint transliterates the product name, so a value that
            # lost it falls back to the source rather than shipping another brand.
            if T.BRAND.search(text) and "ZYRAXON" not in value.upper():
                value = text
            added[text] = value
        # A longer gap between batches: the endpoint starts answering with the
        # source text (which reads as "untranslated") when the whole table is
        # pushed through it back to back.
        time.sleep(1.6)
    table.update(added)
    json.dump(table, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=1, sort_keys=True)
    return len(added), len(table)


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

    fragments = tool_keys()
    print("tool fragments:", len(fragments))

    failures = []
    for lang in languages:
        code = lang["code"]
        if code == "bn":
            continue
        path = os.path.join(OUT_DIR, code + ".json")
        table = json.load(open(path, encoding="utf-8")) if os.path.exists(path) else {}
        # Every key the app can ask for, plus every key already in the table: a
        # stale entry that nothing looks up any more is still repaired, so no
        # language can show Bengali through a path the key list does not see.
        if force:
            missing = sorted(set(keys) | set(table))
        else:
            missing = [
                k
                for k in sorted(set(keys) | set(table))
                if k not in table or (table[k] == k and K.BENGALI.search(k))
            ]
        tools_path = os.path.join(OUT_DIR, "tools-" + code + ".json")
        tools_table = json.load(open(tools_path, encoding="utf-8")) if os.path.exists(tools_path) else {}
        tools_missing = [
            k
            for k in fragments
            if k not in tools_table or (tools_table[k] == k and K.BENGALI.search(k))
        ]
        if not missing and not tools_missing:
            print("%-6s up to date" % code, flush=True)
            continue
        try:
            ui_added = translate_table(path, missing, code) if missing else None
            tool_added = translate_table(tools_path, tools_missing, code) if tools_missing else None
        except Exception as error:
            failures.append("%s: %s" % (code, error))
            print("%-6s FAILED %s" % (code, error), flush=True)
            continue
        if ui_added:
            print("%-6s ui +%d (now %d)" % (code, ui_added[0], ui_added[1]), flush=True)
        if tool_added:
            print("%-6s tools +%d (now %d)" % (code, tool_added[0], tool_added[1]), flush=True)

    if failures:
        print("failed: %s" % ", ".join(failures), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
