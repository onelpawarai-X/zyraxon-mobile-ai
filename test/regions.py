"""Region analysis of the ZYRAXON UI screenshot — proves the video shows
through and the glass panels render, without needing eyes on the image."""
import sys
from PIL import Image, ImageStat


def region_stats(im, box, name):
    crop = im.crop(box).convert('RGB')
    st = ImageStat.Stat(crop)
    colors = crop.getcolors(maxcolors=1000000)
    uniq = len(colors) if colors else 0
    return f"{name}: mean={[round(v) for v in st.mean]} stddev={[round(v) for v in st.stddev]} uniq_colors={uniq}"


path = sys.argv[1] if len(sys.argv) > 1 else '/tmp/ui1.png'
im = Image.open(path)
W, H = im.size
print(f"image {W}x{H}")
print(region_stats(im, (0, 0, W, H), 'full'))
# background strips (left/right edges, below the orb) should show video texture
print(region_stats(im, (0, int(H * 0.35), int(W * 0.06), int(H * 0.75)), 'bg-left'))
print(region_stats(im, (int(W * 0.94), int(H * 0.35), W, int(H * 0.75)), 'bg-right'))
# top bar glass
print(region_stats(im, (int(W * 0.05), int(H * 0.02), int(W * 0.95), int(H * 0.07)), 'topbar'))
# orb
print(region_stats(im, (int(W * 0.3), int(H * 0.13), int(W * 0.7), int(H * 0.33)), 'orb'))
# composer
print(region_stats(im, (int(W * 0.05), int(H * 0.9), int(W * 0.95), int(H * 0.97)), 'composer'))
