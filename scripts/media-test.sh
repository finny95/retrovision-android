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

DEST="/sdcard/Android/data/$PKG/files/tv/Test Show/Season 1"
adb shell mkdir -p "'$DEST'"
FILES=()
for f in "$SAMPLES"/*; do
  adb push "$f" "$DEST/" >/dev/null
  FILES+=("tv/Test Show/Season 1/$(basename "$f")")
done
adb shell ls -l "'$DEST'"

PID=$(adb shell pidof "$PKG" | tr -d '\r')
adb forward tcp:9222 "localabstract:webview_devtools_remote_$PID"
node scripts/media-test.mjs 9222 "$OUT/media-results.json" "${FILES[@]}"
adb exec-out screencap -p > "$OUT/media-channel.png"
adb logcat -d > "$OUT/logcat-full.txt"
grep -E "RetroVisionMedia|\[ota\]|\[native\]|FATAL|chromium.*(Media|media)" "$OUT/logcat-full.txt" | tail -60 > "$OUT/logcat-app.txt" || true
cp "$OUT/media-results.json" "$OUT/results.json" 2>/dev/null || true
