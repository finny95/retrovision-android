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
  adb shell rm -f "/data/local/tmp/$safe"
  FILES+=("tv/$safe")
done
adb shell "run-as $PKG ls -l cache/tv"

run_phase() {
  # (Re)open the app if the emulator closed it, then attach to its WebView.
  adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
  sleep 8
  PID=$(adb shell pidof "$PKG" | tr -d '\r')
  adb forward --remove-all
  adb forward tcp:9222 "localabstract:webview_devtools_remote_$PID"
  echo "== phase $1 (pid $PID)" >> "$OUT/node.log"
  timeout 600 node scripts/media-test.mjs 9222 "$OUT/media-results.json" "$1" "${FILES[@]}" >> "$OUT/node.log" 2>&1
  echo "== exit $?" >> "$OUT/node.log"
}
run_phase files
run_phase channel
adb exec-out screencap -p > "$OUT/media-channel.png"
run_phase audio
adb exec-out screencap -p > "$OUT/ui-portrait.png"
# Sideways, like a handheld: the full-screen TV layout.
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
sleep 4
adb exec-out screencap -p > "$OUT/ui-landscape.png"
# TV remote: OK opens the menu, d-pad moves, RB-less tab change via the tab bar.
adb shell input keyevent KEYCODE_DPAD_CENTER
sleep 2
adb exec-out screencap -p > "$OUT/ui-landscape-menu.png"
adb shell input keyevent KEYCODE_DPAD_UP
adb shell input keyevent KEYCODE_DPAD_UP
adb shell input keyevent KEYCODE_DPAD_RIGHT
adb shell input keyevent KEYCODE_DPAD_RIGHT
sleep 1
adb exec-out screencap -p > "$OUT/ui-landscape-menu2.png"
adb logcat -d > "$OUT/logcat-full.txt"
grep -E "RetroVisionMedia|\[ota\]|\[native\]|FATAL|chromium.*(Media|media)" "$OUT/logcat-full.txt" | tail -60 > "$OUT/logcat-app.txt" || true
cp "$OUT/media-results.json" "$OUT/results.json" 2>/dev/null || true
