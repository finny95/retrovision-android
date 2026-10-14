#!/usr/bin/env bash
# Runs on an emulator with the DEBUG APK installed: pushes sample videos into the
# app's own storage and checks streaming, seeking and channel playback.
# Usage: bash scripts/media-test.sh app-debug.apk out-dir samples-dir
set -u
APK="$1"
OUT="$2"
SAMPLES="$3"
PKG=com.retrovision.player
mkdir -p "$OUT"

adb install -r "$APK"
adb shell am start -W -n "$PKG/.MainActivity"
sleep 12

# Copy samples into the app's own cache (debug build, so run-as works); the
# app's FileProvider then hands out content:// URIs like the folder picker does.
adb shell "run-as $PKG mkdir -p cache/tv"
FILES=()
for f in "$SAMPLES"/*; do
  safe=$(basename "$f" | tr ' ' '_')
  adb push "$f" "/data/local/tmp/$safe" >/dev/null
  adb shell "cat /data/local/tmp/$safe | run-as $PKG sh -c 'cat > cache/tv/$safe'"
  FILES+=("tv/$safe")
done
adb shell "run-as $PKG ls -l cache/tv"

PID=$(adb shell pidof "$PKG" | tr -d '\r')
adb forward tcp:9222 "localabstract:webview_devtools_remote_$PID"
node scripts/media-test.mjs 9222 "$OUT/media-results.json" "${FILES[@]}"
adb exec-out screencap -p > "$OUT/media-channel.png"
adb logcat -d > "$OUT/logcat-full.txt"
grep -E "RetroVisionMedia|\[ota\]|\[native\]|FATAL|chromium.*(Media|media)" "$OUT/logcat-full.txt" | tail -60 > "$OUT/logcat-app.txt" || true
cp "$OUT/media-results.json" "$OUT/results.json" 2>/dev/null || true
