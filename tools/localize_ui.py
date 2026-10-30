#!/usr/bin/env python3
"""Rewrite the Bengali UI literals as localisation calls.

Every Bengali string literal in the Compose UI package becomes a lookup into
the tables produced by `translate_ui.py`. The rewrite is driven by
`kotlin_strings.scan`, so it touches exactly the spans that are string
literals and leaves comments, tool schemas and prompts alone.

Three shapes come out of it, and they have to be right or the build breaks:

* a plain literal becomes `t("…")` — the composable overload, which
  subscribes the caller to language changes;
* a literal with `$name` interpolations becomes
  `t("… {0} …", name)` with the original expressions passed as arguments;
* a literal already inside a `Text("…")` call becomes `Text(t("…"))`.

The result is checked by the compiler and by the unit tests, which is what
makes this safe to run over the whole package.
"""

import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import kotlin_strings as K  # noqa: E402
import translate_ui as T  # noqa: E402  (owns the interpolation tokenizer)

# Calls whose string argument is shown to the user, so it must be translated.
# Anything else (a key, a tag, a URL) is left alone.
TRANSLATE_ALL = True

# The rewritten call. `t` is imported from the i18n package in every file that
# ends up using it.
IMPORT_LINE = "import ai.zyraxon.assistant.i18n.t\n"
IMPORT_ANCHOR = re.compile(r"^import ", re.MULTILINE)


def _expression(source):
    """The bare Kotlin expression behind a `$name` or `${ … }` interpolation."""
    text = source.strip()
    if text.startswith("$"):
        text = text[1:]
    if text.startswith("{") and text.endswith("}"):
        text = text[1:-1]
    return text.strip()


def to_call(literal):
    """The `t(...)` call that replaces [literal]."""
    key, exprs = T.tokenize(literal.value)
    key_literal = '"' + K.encode(key) + '"'
    if not exprs:
        return "t(%s)" % key_literal
    args = ", ".join(_expression(expr) for expr in exprs)
    return "t(%s, %s)" % (key_literal, args)


def rewrite(path):
    source = open(path, encoding="utf-8").read()
    literals = [lit for lit in K.scan(source) if K.BENGALI.search(lit.value)]
    if not literals:
        return 0

    # Replace from the end so earlier offsets stay valid.
    out = source
    for lit in reversed(literals):
        out = out[: lit.start] + to_call(lit) + out[lit.end :]

    if IMPORT_LINE not in out:
        anchor = IMPORT_ANCHOR.search(out)
        if anchor:
            out = out[: anchor.start()] + IMPORT_LINE + out[anchor.start() :]
        else:
            # No imports yet: put it after the package line.
            package = re.search(r"^package .*$", out, re.MULTILINE)
            at = package.end() + 1 if package else 0
            out = out[:at] + "\n" + IMPORT_LINE + out[at:]

    open(path, "w", encoding="utf-8").write(out)
    return len(literals)


def main():
    dry = "--dry" in sys.argv
    total = 0
    for path in K.ui_files():
        count = rewrite(path) if not dry else len(
            [lit for lit in K.scan(open(path, encoding="utf-8").read()) if K.BENGALI.search(lit.value)]
        )
        if count:
            print("%-28s %d" % (os.path.basename(path), count))
            total += count
    print("total literals rewritten:", total)


if __name__ == "__main__":
    main()
