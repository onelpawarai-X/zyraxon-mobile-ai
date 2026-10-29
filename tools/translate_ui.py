#!/usr/bin/env python3
"""Generate the per-language UI string tables shipped in assets/i18n.

The app's UI strings are Bengali, and this produces a table for each of the
languages in languages.json. The tables are plain JSON assets rather than
values-<locale>/strings.xml so a language can be added, corrected or replaced
without a rebuild.

Two things matter for correctness:

* `$name` is Kotlin string interpolation, so it must survive translation
  untouched. `$` is swapped for a `__N__` token, which every script keeps
  verbatim, and swapped back afterwards.
* The translation endpoint returns one string per `q=` parameter in the same
  order, so a batch is a straight index-to-index map. The count is checked
  before anything is written.

Re-running is cheap: a language whose file already exists is skipped unless
--force is passed.
"""

import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import kotlin_strings  # noqa: E402  (same directory, used for literal extraction)

ROOT = kotlin_strings.ROOT
LANGS_FILE = os.path.join(ROOT, "tools", "languages.json")
OUT_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "i18n")

BATCH = 40
ENDPOINT = "https://clients5.google.com/translate_a/t"
SOURCE = "bn"


def tokenize(text):
    """Turn every Kotlin interpolation into a positional `{N}` token.

    Kotlin evaluates `"… $x …"` before the localisation call ever runs, so the
    runtime never sees a `$`. The table key therefore has to be the *evaluated*
    shape: `$name` becomes `{0}`, `${expr}` becomes `{0}`, and each placeholder
    is numbered by the order it appears.

    Returns the key and the ordered list of expression sources, which become the
    arguments of the generated `Loc.t(...)` call.
    """
    exprs = []

    def repl(match):
        exprs.append(match.group(0))
        return "{%d}" % (len(exprs) - 1)

    # Braced form first, so the `$name` inside it is not matched separately.
    masked = re.sub(r"\$\{[^}]*\}", repl, text)
    return re.sub(r"\$([A-Za-z_][A-Za-z0-9_]*)", repl, masked), exprs


def protect(text):
    """Mask the `{N}` tokens so the translator cannot renumber or drop them."""
    names = []

    def repl(match):
        names.append(match.group(0))
        return "__%d__" % (len(names) - 1)

    return re.sub(r"\{\d+\}", repl, text), names


def restore(text, names):
    for index, name in enumerate(names):
        text = text.replace("__%d__" % index, name)
    return text


def fetch(batch, target, attempt=0):
    query = "&".join("q=" + urllib.parse.quote(text) for text in batch)
    url = "%s?client=dict-chrome-ex&sl=%s&tl=%s&%s" % (ENDPOINT, SOURCE, target, query)
    request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    try:
        with urllib.request.urlopen(request, timeout=45) as response:
            payload = json.load(response)
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as error:
        if attempt >= 4:
            raise RuntimeError("translate failed for %s: %s" % (target, error))
        time.sleep(2 * (attempt + 1))
        return fetch(batch, target, attempt + 1)

    if isinstance(payload, str):
        payload = [payload]
    if len(payload) != len(batch):
        raise RuntimeError(
            "asked for %d strings from %s, got %d" % (len(batch), target, len(payload))
        )
    return payload


def translate_language(code, keys, force):
    path = os.path.join(OUT_DIR, code + ".json")
    if os.path.exists(path) and not force:
        return "skip (exists)"

    table = {}
    for start in range(0, len(keys), BATCH):
        chunk = keys[start : start + BATCH]
        protected = []
        names = []
        for text in chunk:
            masked, found = protect(text)
            protected.append(masked)
            names.append(found)

        results = fetch(protected, code)
        for text, masked, translated, found in zip(chunk, protected, results, names):
            value = restore(translated, found).strip()
            # A translation that lost or duplicated a placeholder would print a
            # broken sentence, and an empty one would blank the label. Both fall
            # back to the Bengali source, which is always readable.
            if not value or any(value.count(name) != 1 for name in found):
                value = text
            table[text] = value
        time.sleep(0.6)

    with open(path, "w", encoding="utf-8") as handle:
        json.dump(table, handle, ensure_ascii=False, indent=1, sort_keys=True)
    return "%d strings" % len(table)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--force", action="store_true", help="redo languages already on disk")
    parser.add_argument("--only", help="comma-separated language codes")
    args = parser.parse_args()

    keys = kotlin_strings.collect_keys()
    languages = json.load(open(LANGS_FILE, encoding="utf-8"))
    os.makedirs(OUT_DIR, exist_ok=True)

    # The tables are keyed by the evaluated string, so `$name` is `{0}` here and
    # the generated Kotlin passes the real value in that slot.
    table_keys = {}
    for raw in keys:
        key, _ = tokenize(raw)
        table_keys[key] = raw
    json.dump(
        table_keys,
        open(os.path.join(ROOT, "tools", "ui_keys.json"), "w", encoding="utf-8"),
        ensure_ascii=False,
        indent=0,
        sort_keys=True,
    )

    if args.only:
        wanted = set(args.only.split(","))
        languages = [lang for lang in languages if lang["code"] in wanted]

    # Bengali is the source language, so its table is the identity map.
    with open(os.path.join(OUT_DIR, "bn.json"), "w", encoding="utf-8") as handle:
        json.dump({key: key for key in table_keys}, handle, ensure_ascii=False, indent=1, sort_keys=True)

    failures = []
    tokenized = sorted(table_keys)
    for index, lang in enumerate(languages, 1):
        code = lang["code"]
        if code == "bn":
            continue
        try:
            result = translate_language(code, tokenized, args.force)
        except Exception as error:  # keep going; one bad language must not stop the run
            result = "FAILED: %s" % error
            failures.append(code)
        print("[%2d/%d] %-6s %s" % (index, len(languages), code, result), flush=True)

    if failures:
        print("failed: %s" % ", ".join(failures), file=sys.stderr)
        return 1
    print("all %d languages written to %s" % (len(languages), OUT_DIR))
    return 0


if __name__ == "__main__":
    sys.exit(main())
