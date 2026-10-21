#!/usr/bin/env bash
#
# Re-encode the bundled background clips so the app can never lag on them.
#
# The originals were mostly VP8/VP9 WebM, and two of them were far heavier than
# a background has any right to be: nightlights was 3840x2160 VP9 and city was
# 1920x1080 VP9 at 4.5 Mb/s. Browsers have no hardware VP9 decoder on plenty of
# machines, so every one of those frames was decoded on the CPU — that, not the
# CSS blur, was what made the desktop shell crawl.
#
# Everything is rewritten as H.264 High/Main profile yuv420p at a sensible
# resolution and a low bitrate. H.264 is the one codec with hardware decode
# almost everywhere, including the old Android WebViews this app targets.
#
# Run:  bash scripts/optimize-media.sh
# Needs ffmpeg on PATH, or FFMPEG=/path/to/ffmpeg.
set -u

cd "$(dirname "$0")/.." || exit 1

FF="${FFMPEG:-ffmpeg}"
if ! command -v "$FF" >/dev/null 2>&1; then
  echo "ffmpeg not found. Set FFMPEG=/path/to/ffmpeg" >&2
  exit 1
fi

BG=public/assets/bg
GT=public/assets/gt
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# crf is deliberately generous: these are soft, dark, ambient loops shown
# behind a blurred glass UI, so detail nobody can see is pure cost.
#
# `tag` prefixes the temp filename. Two different sources are both called
# city.*, so without it the second encode silently clobbered the first.
encode() {
  local tag="$1" in="$2" w="$3" h="$4" crf="$5" fps="$6"
  local out="$WORK/$tag-$(basename "${in%.*}").mp4"
  local vf="scale=${w}:${h}:flags=lanczos,fps=${fps}"
  # HLG/bt2020 sources (rain) come out washed out unless they are tonemapped
  # down to bt709 first, which is the only colour space the rest of the app uses.
  local tm=""
  if ffprobe_hlg "$in"; then
    tm="zscale=t=linear:npl=100,format=gbrpf32le,zscale=p=bt709,tonemap=tonemap=hable:desat=0,zscale=t=bt709:m=bt709:r=tv,format=yuv420p,"
  fi
  "$FF" -hide_banner -loglevel error -y -i "$in" \
    -vf "${tm}${vf}" \
    -c:v libx264 -profile:v main -level:v 3.1 -pix_fmt yuv420p \
    -preset slow -crf "$crf" -g $((fps * 4)) \
    -movflags +faststart -an \
    "$out" || return 1
  printf '  %-34s %6s -> %6s\n' "$(basename "$in")" \
    "$(du -h "$in" | cut -f1)" "$(du -h "$out" | cut -f1)"
}

ffprobe_hlg() {
  local probe="${FF%ffmpeg}ffprobe"
  command -v "$probe" >/dev/null 2>&1 || return 1
  "$probe" -v error -select_streams v:0 -show_entries stream=color_transfer \
    -of csv=p=0 "$1" 2>/dev/null | grep -qi 'arib-std-b67\|smpte2084'
}

echo "== landscape backgrounds (720x406 source, kept as-is) =="
for name in aurora city dream hero ink space; do
  encode bg "$BG/$name.mp4" 720 406 30 24
done

echo "== portrait character clips (1080x1920 -> 720x1280) =="
# The girlfriend clip is the character the user actually looks at, so it keeps
# more of its detail than the abstract loops.
encode bg "$BG/girl.mp4" 720 1280 26 30
encode bg "$BG/galaxy-eye.mp4" 720 1280 27 30

echo "== heavy WebM sources =="
encode gt "$GT/city.webm" 1280 720 30 24
encode gt "$GT/nebula.webm" 1280 720 30 24
encode gt "$GT/nightlights.webm" 1280 720 30 3
encode gt "$GT/rain.webm" 1280 720 30 24

echo "== installing =="
for f in "$WORK"/bg-*.mp4; do mv "$f" "$BG/$(basename "$f" | sed 's/^bg-//')"; done
for f in "$WORK"/gt-*.mp4; do mv "$f" "$GT/$(basename "$f" | sed 's/^gt-//')"; done

# The .webm originals are gone now that bg.ts points at the .mp4 files.
rm -f "$GT"/*.webm

echo "== done =="
du -sh "$BG" "$GT"
