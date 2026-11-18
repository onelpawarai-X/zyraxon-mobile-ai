# -*- coding: utf-8 -*-
"""Fill the Documentation-screen table (`assets-src/i18n/hub/`) for every language.

The table is keyed by Bengali — the identity language — so the keys stay exactly
as they are and only the English value is translated. The same marker trick as
the guide is used, so backticked names, arrows, placeholders and product names
come back untouched.

Usage: python3 tools/_hub_translate.py (or `de fr ...`)
"""
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HUB = os.path.join(ROOT, "assets-src", "i18n", "hub")
LANGS = os.path.join(ROOT, "assets-src", "i18n", "languages.json")
ENDPOINT = "https://clients5.google.com/translate_a/t"
BATCH = 30
HAND_WRITTEN = {"bn", "en"}


def fetch(batch, target, attempt=0):
    query = "&".join("q=" + urllib.parse.quote(t) for t in batch)
    url = "%s?client=dict-chrome-ex&sl=en&tl=%s&%s" % (ENDPOINT, target, query)
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            payload = json.load(resp)
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as err:
        if attempt >= 5:
            raise RuntimeError("hub translate failed for %s: %s" % (target, err))
        time.sleep(2 * (attempt + 1))
        return fetch(batch, target, attempt + 1)
    if isinstance(payload, str):
        payload = [payload]
    if len(payload) != len(batch):
        if attempt >= 5:
            raise RuntimeError("asked %d got %d for %s" % (len(batch), len(payload), target))
        time.sleep(2)
        return fetch(batch, target, attempt + 1)
    return payload


def run(code, source, fill=False):
    path = os.path.join(HUB, code + ".json")
    existing = {}
    if fill and os.path.exists(path):
        existing = json.load(open(path, encoding="utf-8"))
    # Translate only what is missing, or what is present but blank. An existing
    # hand-written value is never replaced.
    todo = [k for k in source if k not in existing or not str(existing[k]).strip()]
    out = dict(existing)
    for start in range(0, len(todo), BATCH):
        chunk = todo[start : start + BATCH]
        answers = fetch([source[k] for k in chunk], code)
        for key, answer in zip(chunk, answers):
            out[key] = answer
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(out, fh, ensure_ascii=False, indent=1, sort_keys=True)
    return len(todo)


def main():
    source = json.load(open(os.path.join(HUB, "en.json"), encoding="utf-8"))
    args = sys.argv[1:]
    fill = False
    if "--fill" in args:
        args.remove("--fill")
        fill = True
    codes = [l["code"] for l in json.load(open(LANGS, encoding="utf-8"))] if args[:1] == ["all"] else args
    for code in codes:
        if code in HAND_WRITTEN:
            continue
        if not fill and os.path.exists(os.path.join(HUB, code + ".json")):
            continue
        n = run(code, source, fill)
        print("%s: %d strings filled" % (code, n), flush=True)


if __name__ == "__main__":
    main()
