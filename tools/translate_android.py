#!/usr/bin/env python3
"""Generate values-<locale>/strings.xml for the strings Android itself reads.

The in-app UI is localised from `assets/i18n`, but a few strings are rendered
by the system rather than by Compose — the accessibility service's name and
description in Settings, for instance. Those must be real Android resources,
so this writes one `values-<qualifier>` directory per language.

Android's resource qualifiers are not BCP-47: a region is written `rXX`, so
`zh-CN` becomes `values-zh-rCN`.
"""

import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.sax.saxutils as sax

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import kotlin_strings as K  # noqa: E402

ROOT = K.ROOT
RES = os.path.join(ROOT, "app", "src", "main", "res")
SOURCE_XML = os.path.join(RES, "values", "strings.xml")
LANGS_FILE = os.path.join(ROOT, "tools", "languages.json")
ENDPOINT = "https://clients5.google.com/translate_a/t"


def qualifier(code):
    """The Android resource qualifier for a BCP-47 language tag."""
    parts = code.split("-")
    if len(parts) == 1:
        return parts[0]
    return "%s-r%s" % (parts[0], parts[1].upper())


def source_strings():
    xml = open(SOURCE_XML, encoding="utf-8").read()
    return re.findall(r'<string name="([^"]+)">(.*?)</string>', xml, re.DOTALL)


def translate(texts, target, attempt=0):
    query = "&".join("q=" + urllib.parse.quote(text) for text in texts)
    url = "%s?client=dict-chrome-ex&sl=bn&tl=%s&%s" % (ENDPOINT, target, query)
    request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    try:
        with urllib.request.urlopen(request, timeout=45) as response:
            payload = json.load(response)
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as error:
        if attempt >= 4:
            raise RuntimeError("%s: %s" % (target, error))
        time.sleep(2 * (attempt + 1))
        return translate(texts, target, attempt + 1)
    if isinstance(payload, str):
        payload = [payload]
    if len(payload) != len(texts):
        raise RuntimeError("asked %d, got %d for %s" % (len(texts), len(payload), target))
    return payload


def main():
    strings = source_strings()
    # A name that is already a brand ("ZYRAXON") must not be translated.
    translatable = [(name, value) for name, value in strings if K.BENGALI.search(value)]
    languages = json.load(open(LANGS_FILE, encoding="utf-8"))

    for lang in languages:
        code = lang["code"]
        if code == "bn":
            continue
        out_dir = os.path.join(RES, "values-" + qualifier(code))
        os.makedirs(out_dir, exist_ok=True)
        try:
            values = translate([value for _, value in translatable], code)
        except Exception as error:
            print("%-8s FAILED %s" % (code, error))
            continue
        lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
        for (name, source), value in zip(translatable, values):
            escaped = sax.escape(value, {'"': "&quot;", "'": "\\'"})
            lines.append('    <string name="%s">%s</string>' % (name, escaped))
        lines.append("</resources>")
        open(os.path.join(out_dir, "strings.xml"), "w", encoding="utf-8").write("\n".join(lines) + "\n")
        print("%-8s %d strings -> values-%s" % (code, len(values), qualifier(code)))
        time.sleep(0.6)


if __name__ == "__main__":
    main()
