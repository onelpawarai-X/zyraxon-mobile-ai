#!/usr/bin/env python3
"""Read a screenshot: report what it is, then OCR it in English and Bengali.

Usage:
    python3 tools/read_screenshot.py IMAGE [IMAGE ...] [options]

Options:
    --crop L T R B     read only this region instead of the whole image
    --scale N          upscale factor before OCR (default 2)
    --psm N            tesseract page-segmentation mode (default 3)
    --langs L          tesseract languages (default "eng+ben")
    --bands            read the image in overlapping horizontal bands as well
                       as whole, and merge the lines (default on)
    --band-height N    band height in source pixels (default 420)
    --band-overlap N   overlap between bands (default 120)
    --save-prepped DIR write the preprocessed image here to inspect it

Why upscale: tesseract wants roughly 30 px of glyph height to read reliably. A
1080 px phone screenshot carries 12-14 px UI text, so reading it at native size
throws away exactly the small labels this is meant to catch.

Why bands: on a chat screen, text sits on coloured bubbles whose local contrast
is far below the page's, so a whole-page pass (psm 3) segments the layout and
drops a bubble's text entirely. Each band has its own contrast range, so a band
pass recovers those lines. Bands overlap so a line cut in half by a boundary
still appears whole in one of the two bands.
"""

import argparse
import subprocess
import sys
from pathlib import Path

try:
    from PIL import Image, ImageFilter, ImageOps
except ImportError:
    sys.exit("Pillow is required: pip install pillow")


def prep(img, scale):
    """Upscale, flatten to grayscale, stretch contrast, sharpen.

    Autocontrast matters for dark-mode screenshots, where the text is light on
    dark and the raw histogram is narrow.
    """
    w, h = img.size
    img = img.convert("L")
    img = img.resize((w * scale, h * scale), Image.LANCZOS)
    img = ImageOps.autocontrast(img, cutoff=1)
    return img.filter(ImageFilter.SHARPEN)


def ocr(img, psm, langs, tmp):
    img.save(tmp)
    out = subprocess.run(
        ["tesseract", str(tmp), "stdout", "-l", langs, "--psm", str(psm)],
        capture_output=True,
        text=True,
    )
    if out.returncode != 0:
        return ["[tesseract failed: %s]" % out.stderr.strip()]
    return [ln.strip() for ln in out.stdout.splitlines() if ln.strip()]


def bands(img, height, overlap):
    """Yield (top, bottom) windows down the image, each overlapping the last."""
    h = img.size[1]
    if h <= height:
        yield 0, h
        return
    step = height - overlap
    top = 0
    while True:
        bottom = min(top + height, h)
        yield top, bottom
        if bottom >= h:
            return
        top += step


def read(path, args, tmp):
    with Image.open(path) as raw:
        info = "%dx%d %s %s" % (raw.width, raw.height, raw.mode, raw.format)
        img = raw.copy()

    if args.crop:
        img = img.crop(tuple(args.crop))

    print("== %s ==" % path.name)
    print("  image: %s" % info)
    if args.crop:
        print("  crop:  %s" % (args.crop,))

    seen = []

    whole = ocr(prep(img, args.scale), args.psm, args.langs, tmp)
    kept = []
    for ln in whole:
        if ln not in seen:
            seen.append(ln)
            kept.append(ln)
    print("  whole page (psm=%d, scale=%dx): %d lines" % (args.psm, args.scale, len(kept)))
    for ln in kept:
        print("    %s" % ln)

    if args.bands:
        extra = []
        for top, bottom in bands(img, args.band_height, args.band_overlap):
            if bottom - top < 60:
                break
            strip = img.crop((0, top, img.width, bottom))
            for ln in ocr(prep(strip, args.scale), 6, args.langs, tmp):
                if ln not in seen and ln not in extra:
                    extra.append(ln)
        print("  bands (overlap %dpx): %d further lines" % (args.band_overlap, len(extra)))
        for ln in extra:
            print("    %s" % ln)

    if args.save_prepped:
        d = Path(args.save_prepped)
        d.mkdir(parents=True, exist_ok=True)
        prep(img, args.scale).save(d / ("%s-prepped.png" % path.stem))
        print("  prepped image written to %s/%s-prepped.png" % (d, path.stem))

    print()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("images", nargs="+")
    ap.add_argument("--crop", type=int, nargs=4, metavar=("L", "T", "R", "B"))
    ap.add_argument("--scale", type=int, default=2)
    ap.add_argument("--psm", type=int, default=3)
    ap.add_argument("--langs", default="eng+ben")
    ap.add_argument("--bands", action="store_true", default=True)
    ap.add_argument("--no-bands", dest="bands", action="store_false")
    ap.add_argument("--band-height", type=int, default=420)
    ap.add_argument("--band-overlap", type=int, default=120)
    ap.add_argument("--save-prepped", metavar="DIR")
    args = ap.parse_args()

    tmp = Path("/tmp/_ocr_prepped.png")
    for path in args.images:
        p = Path(path)
        if not p.is_file():
            print("== %s ==\n  not a file\n" % path)
            continue
        read(p, args, tmp)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
