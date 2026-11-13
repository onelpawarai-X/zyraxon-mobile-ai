#!/usr/bin/env python3
"""Find and rewrite the Bengali string literals in the Compose UI.

The literals are what the app localises, so both the extraction and the rewrite
have to agree on exactly which spans of source are string literals. A regex
cannot do that here: Kotlin allows a string to contain another string inside an
interpolation (`"${String.format("%.1f", x)}"`), and Bengali text also appears
in comments, which must be left alone.

`scan` therefore walks the source the way the Kotlin lexer does — skipping line
and block comments, skipping `\"` escapes, skipping triple-quoted strings, and
tracking brace depth inside `${ … }` so a nested string ends where it should.

Everything else in this file is built on that one function.
"""

import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BENGALI = re.compile("[\u0980-\u09FF]")

# The Compose UI package. Model-facing text (tool schemas, system prompts) lives
# outside it and is deliberately not localised.
UI_ROOT = os.path.join(ROOT, "app", "src", "main", "java", "ai", "zyraxon", "assistant", "ui")


class Literal:
    """One string literal, with the span needed to replace it."""

    __slots__ = ("start", "end", "raw", "value")

    def __init__(self, start, end, raw):
        self.start = start
        self.end = end
        self.raw = raw
        # The table is looked up at runtime with the *evaluated* string, so the
        # key has to be the decoded one: `\"` in the source is `"` at runtime.
        self.value = decode(raw)

    def __repr__(self):
        return "Literal(%d,%d,%r)" % (self.start, self.end, self.value)


_ESCAPES = {
    "t": "\t",
    "b": "\b",
    "n": "\n",
    "r": "\r",
    "0": "\0",
    "'": "'",
    '"': '"',
    "\\": "\\",
    "$": "$",
}


def decode(raw):
    """The runtime value of a Kotlin string literal body."""
    out = []
    index = 0
    while index < len(raw):
        char = raw[index]
        if char == "\\" and index + 1 < len(raw):
            nxt = raw[index + 1]
            if nxt == "u" and index + 5 < len(raw) + 1:
                try:
                    out.append(chr(int(raw[index + 2 : index + 6], 16)))
                    index += 6
                    continue
                except ValueError:
                    pass
            if nxt in _ESCAPES:
                out.append(_ESCAPES[nxt])
                index += 2
                continue
        out.append(char)
        index += 1
    return "".join(out)


def encode(value):
    """Escape a runtime string back into a Kotlin double-quoted literal body."""
    out = []
    for char in value:
        if char == "\\":
            out.append("\\\\")
        elif char == '"':
            out.append('\\"')
        elif char == "$":
            # A bare `$` before a letter would be read as an interpolation.
            out.append("\\$")
        elif char == "\n":
            out.append("\\n")
        elif char == "\t":
            out.append("\\t")
        elif char == "\r":
            out.append("\\r")
        else:
            out.append(char)
    return "".join(out)


def scan(source):
    """Every double-quoted literal in [source], in source order.

    Triple-quoted strings are skipped: they hold tool schemas and prompts, which
    are read by the model rather than by the user.
    """
    found = []
    index = 0
    length = len(source)

    while index < length:
        char = source[index]

        if char == "/" and index + 1 < length and source[index + 1] == "/":
            newline = source.find("\n", index)
            index = length if newline < 0 else newline + 1
            continue

        if char == "/" and index + 1 < length and source[index + 1] == "*":
            close = source.find("*/", index + 2)
            index = length if close < 0 else close + 2
            continue

        if source.startswith('"""', index):
            close = source.find('"""', index + 3)
            index = length if close < 0 else close + 3
            continue

        if char == '"':
            start = index
            index += 1
            while index < length:
                current = source[index]
                if current == "\\":
                    index += 2
                    continue
                if current == '"':
                    index += 1
                    break
                if current == "$" and index + 1 < length and source[index + 1] == "{":
                    index = _skip_braced(source, index + 1)
                    continue
                index += 1
            found.append(Literal(start, index, source[start + 1 : index - 1]))
            continue

        index += 1

    return found


def _skip_braced(source, brace):
    """Return the index just past the `}` matching the `{` at [brace]."""
    depth = 0
    index = brace
    length = len(source)
    while index < length:
        char = source[index]
        if char == '"':
            index += 1
            while index < length:
                if source[index] == "\\":
                    index += 2
                    continue
                if source[index] == '"':
                    break
                index += 1
        elif char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return index + 1
        index += 1
    return length


def bengali_literals(path):
    source = open(path, encoding="utf-8").read()
    return [lit for lit in scan(source) if BENGALI.search(lit.value)]


def ui_files():
    for base, _, names in os.walk(UI_ROOT):
        for name in sorted(names):
            if name.endswith(".kt"):
                yield os.path.join(base, name)


def collect_keys():
    keys = set()
    for path in ui_files():
        for lit in bengali_literals(path):
            keys.add(lit.value)
    return sorted(keys)


if __name__ == "__main__":
    keys = collect_keys()
    json.dump(keys, open(os.path.join(ROOT, "tools", "ui_keys.json"), "w"), ensure_ascii=False, indent=0)
    print("unique Bengali UI literals:", len(keys))
    nested = [k for k in keys if k.count('"') or "$" in k]
    print("with interpolation or nested quotes:", len(nested))
    for item in nested:
        print("   ", item[:100])
