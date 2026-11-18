# -*- coding: utf-8 -*-
"""Translate the bundled help chapters into the remaining app languages.

The English chapter is the source. Each line is translated on its own so the
shape of the file survives: a blank line stays blank, `## ` keeps its heading
marker, `- ` keeps its bullet marker. Everything the translator must not touch —
backticked tool names, the arrow, numbers, placeholders and product names — is
swapped for a `[[N]]` marker first and put back afterwards. That is the same
trick `tools/translate_ui.py` uses for the UI tables, for the same reason: the
endpoint rewrites a bare `open_app` or a domain into something unusable.

Usage: python3 tools/_guide_translate.py de fr ... (or `all`)
"""
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HELP = os.path.join(ROOT, "assets-src", "help")
LANGS = os.path.join(ROOT, "assets-src", "i18n", "languages.json")
ENDPOINT = "https://clients5.google.com/translate_a/t"
BATCH = 25

# Written out by hand, so they are never a translation target. Arabic is here
# because it deliberately uses no arrow, which the structure check below reads
# as a missing mark and would otherwise "repair" by overwriting the file.
HAND_WRITTEN = {"en", "bn", "hi", "ar", "es", "fr"}

# Words and shapes the translator must not rewrite. Kept as one alternation so
# the order they appear in is the order they come back.
PROTECTED = re.compile(
    r"`[^`]+`"                                   # a backticked tool or setting
    r"|\{\d+\}"                                  # a placeholder
    r"|→"                                        # the arrow
    r"|\b(?:ZYRAXON|Apex|Groq|Gemini|OpenAI|Grok|Claude|DeepSeek|OpenRouter"
    r"|OpenCode|MCP|GitHub|Cloudflare|Canva|Supabase|Shizuku|Linux|Android"
    r"|Windows|macOS|Telegram|WhatsApp|Messenger|YouTube|Instagram|Gmail"
    r"|Play Protect|Play Store|Word|Excel|PowerPoint|PDF|Markdown|CSV|JSON"
    r"|Git|ffmpeg|Python|Node|busybox|Gemma|FunctionGemma|Qwen3|Hugging Face"
    r"|PayPal|M-Pesa|UPI|Pix|bKash|SQL|APK|Wi-Fi|LAN|Google|localhost)\b"
    r"|\b(?:https?://)?(?:[a-z0-9-]+\.)+[a-z]{2,}(?:/[^\s]*)?"
)
MASKED = re.compile(r"\[{1,2}\s*(\d+)\s*\]{1,2}")


def protect(text):
    names = []

    def repl(match):
        names.append(match.group(0))
        return "[[%d]]" % (len(names) - 1)

    return PROTECTED.sub(repl, text), names


def restore(text, names):
    def repl(match):
        i = int(match.group(1))
        return names[i] if i < len(names) else match.group(0)

    return MASKED.sub(repl, text)


def fetch(batch, target, attempt=0):
    query = "&".join("q=" + urllib.parse.quote(t) for t in batch)
    url = "%s?client=dict-chrome-ex&sl=en&tl=%s&%s" % (ENDPOINT, target, query)
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            payload = json.load(resp)
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as err:
        if attempt >= 5:
            raise RuntimeError("translate failed for %s: %s" % (target, err))
        time.sleep(2 * (attempt + 1))
        return fetch(batch, target, attempt + 1)
    if isinstance(payload, str):
        payload = [payload]
    if len(payload) != len(batch):
        if attempt >= 5:
            raise RuntimeError("asked %d, got %d for %s" % (len(batch), len(payload), target))
        time.sleep(2)
        return fetch(batch, target, attempt + 1)
    return payload


def translate_body(text, target):
    """One line's body: protect, translate, restore."""
    masked, names = protect(text)
    if not masked.strip():
        return text
    out = fetch([masked], target)[0]
    return restore(out, names)


def translate_chapter(text, target):
    """Translate one chapter. Only the body of a line is sent, so the markers
    (`## `, `- `) and the blank lines are never the translator's to change."""
    lines = text.replace("\r", "").split("\n")
    out = [""] * len(lines)
    todo = []  # (index, prefix, body, names)
    for i, line in enumerate(lines):
        if not line.strip():
            out[i] = line
            continue
        if line.startswith("## "):
            prefix, body = "## ", line[3:]
        elif line.startswith("- "):
            prefix, body = "- ", line[2:]
        else:
            prefix, body = "", line
        masked, names = protect(body)
        todo.append((i, prefix, masked, names))

    for start in range(0, len(todo), BATCH):
        chunk = todo[start : start + BATCH]
        got = fetch([c[2] for c in chunk], target)
        for (i, prefix, _masked, names), answer in zip(chunk, got):
            out[i] = prefix + restore(answer, names)
    return "\n".join(out)


def translate_chapter_linewise(text, target):
    """Fallback for a line with many markers: one request per line, which the
    endpoint handles without merging or dropping a `[[N]]`."""
    lines = text.replace("\r", "").split("\n")
    out = []
    for line in lines:
        if not line.strip():
            out.append(line)
            continue
        if line.startswith("## "):
            out.append("## " + translate_body(line[3:], target))
        elif line.startswith("- "):
            out.append("- " + translate_body(line[2:], target))
        else:
            out.append(translate_body(line, target))
    return "\n".join(out)


def translate_segmented(body, target):
    """Last resort for a line the endpoint keeps mangling: send only the plain
    text between markers, never a marker itself, so none can be lost."""
    parts = []
    last = 0
    for m in PROTECTED.finditer(body):
        if m.start() > last:
            parts.append(("t", body[last:m.start()]))
        parts.append(("k", m.group(0)))
        last = m.end()
    if last < len(body):
        parts.append(("t", body[last:]))

    idx = [i for i, p in enumerate(parts) if p[0] == "t" and len(p[1].strip()) > 2 and re.search(r"[^\W\d_]", p[1])]
    if idx:
        answers = fetch([parts[i][1] for i in idx], target)
        for i, answer in zip(idx, answers):
            parts[i] = ("t", answer)
    return "".join(p[1] for p in parts)


def translate_chapter_segmented(text, target):
    lines = text.replace("\r", "").split("\n")
    out = []
    for line in lines:
        if not line.strip():
            out.append(line)
            continue
        if line.startswith("## "):
            out.append("## " + translate_segmented(line[3:], target))
        elif line.startswith("- "):
            out.append("- " + translate_segmented(line[2:], target))
        else:
            out.append(translate_segmented(line, target))
    return "\n".join(out)


def check(en, got):
    def marks(t):
        return (
            len(t.splitlines()),
            t.count("\n## ") + (1 if t.startswith("## ") else 0),
            sum(1 for l in t.splitlines() if l.startswith("- ")),
            t.count("`"),
            t.count("→"),
        )
    return marks(en) == marks(got)


def run(code):
    src = os.path.join(HELP, "en")
    dst = os.path.join(HELP, code)
    os.makedirs(dst, exist_ok=True)
    bad = []
    for name in sorted(os.listdir(src)):
        en = open(os.path.join(src, name), encoding="utf-8").read()
        done = os.path.join(dst, name)
        if os.path.exists(done):
            got = open(done, encoding="utf-8").read()
            if check(en, got):
                continue
        got = translate_chapter(en, code)
        if not check(en, got):
            got = translate_chapter_linewise(en, code)
        if not check(en, got):
            got = translate_chapter_segmented(en, code)
        if not check(en, got):
            bad.append(name)
        with open(done, "w", encoding="utf-8") as fh:
            fh.write(got)
    return bad


def main():
    args = sys.argv[1:]
    codes = [l["code"] for l in json.load(open(LANGS, encoding="utf-8"))] if args[:1] == ["all"] else args
    have = set(os.listdir(HELP))
    for code in codes:
        if code in HAND_WRITTEN:
            print("--- %s: hand-written, skipped ---" % code, flush=True)
            continue
        print("--- %s ---" % code, flush=True)
        bad = run(code)
        print("%s: %d chapters, bad structure: %s" % (code, len(os.listdir(os.path.join(HELP, code))), bad or "none"), flush=True)


if __name__ == "__main__":
    main()
