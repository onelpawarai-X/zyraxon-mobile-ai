#!/usr/bin/env bash
# Build the ZYRAXON Android APK without Gradle (aapt2 + javac + d8 + apksigner).
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
BT="$ANDROID_HOME/build-tools/36.0.0"
PLATFORM="$ANDROID_HOME/platforms/android-34/android.jar"
OUT="$HERE/build"
APP="$HERE/app/src/main"

rm -rf "$OUT"
mkdir -p "$OUT/compiled" "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/apk"

echo "1/6 staging web assets"
rm -rf "$APP/assets"
mkdir -p "$APP/assets"
cp -r "$HERE/../public/." "$APP/assets/"
# The screenshot gallery is a web-only marketing page; keep it out of the APK.
rm -rf "$APP/assets/shots"
# The in-browser Whisper engine (transformers.js + onnxruntime wasm) is ~48MB and
# only needed on desktop, where there is no Web Speech at all. Android has a real
# native recogniser, so shipping this would add 48MB to the APK for nothing.
rm -rf "$APP/assets/vendor/transformers"
# Never nest the built APK inside itself.
rm -f "$APP/assets"/*.apk "$APP/assets"/*.apk.idsig
cp "$HERE/../public/manifest.webmanifest" "$APP/assets/manifest.webmanifest" 2>/dev/null || true

echo "2/6 compiling resources"
"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/compiled/res.zip"

echo "3/6 linking resources"
"$BT/aapt2" link \
  -o "$OUT/apk/base.apk" \
  -I "$PLATFORM" \
  --manifest "$APP/AndroidManifest.xml" \
  --java "$OUT/gen" \
  -A "$APP/assets" \
  --min-sdk-version 21 --target-sdk-version 34 \
  -R "$OUT/compiled/res.zip" \
  --auto-add-overlay

echo "4/6 compiling java"
find "$APP/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
# No -bootclasspath: compile against the JDK so lambdas/desugar helpers resolve,
# then d8 desugars them against android.jar below.
javac -nowarn -source 8 -target 8 -encoding UTF-8 \
  -classpath "$PLATFORM" \
  -d "$OUT/classes" @"$OUT/sources.txt"
if [ $? -ne 0 ]; then echo "JAVAC FAILED"; exit 1; fi

echo "5/6 dexing"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --release --lib "$PLATFORM" --min-api 24 --output "$OUT/dex" @"$OUT/classes.txt"

echo "6/6 packaging + signing"
cd "$OUT/apk"
cp base.apk unsigned.apk
zip -q -j unsigned.apk "$OUT/dex/classes.dex"

"$BT/zipalign" -f -p 4 unsigned.apk aligned.apk
"$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" \
  --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey \
  --out "$HERE/zyraxon.apk" aligned.apk
"$BT/apksigner" verify --print-certs "$HERE/zyraxon.apk" | head -3

echo
echo "APK ready: $HERE/zyraxon.apk"
ls -lh "$HERE/zyraxon.apk"
