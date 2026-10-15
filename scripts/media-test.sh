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

adb install -r "$APK" 2>&1 | tail -2 | tee "$OUT/install.txt"
adb shell am start -W -n "$PKG/.MainActivity"
sleep 12
# Early diagnostics, in case a later step hangs.
timeout 20 adb shell getprop sys.boot_completed > "$OUT/boot.txt" 2>&1
timeout 20 adb shell pidof "$PKG" >> "$OUT/boot.txt" 2>&1
timeout 30 adb exec-out screencap -p > "$OUT/0-start.png"
timeout 20 adb shell top -b -n 1 -m 15 > "$OUT/top-start.txt" 2>&1
timeout 30 adb logcat -d > "$OUT/logcat-start.txt" 2>&1
timeout 20 adb devices -l >> "$OUT/boot.txt" 2>&1
if ! timeout 20 adb shell pidof "$PKG" >/dev/null 2>&1; then
  echo "App is not running after launch; stopping early" >> "$OUT/boot.txt"
  grep -E "chromium|cr_|AndroidRuntime|FATAL|RetroVision|Capacitor|libc|DEBUG|gfxstream|EGL|GL" "$OUT/logcat-start.txt" | tail -150 > "$OUT/logcat-app.txt" || true
  cp "$OUT/logcat-start.txt" "$OUT/logcat-full.txt"
  exit 0
fi

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
  timeout 240 node scripts/media-test.mjs 9222 "$OUT/media-results.json" "$1" "${FILES[@]}" >> "$OUT/node.log" 2>&1
  echo "== exit $?" >> "$OUT/node.log"
  adb shell top -b -n 1 -m 8 > "$OUT/top-$1.txt" 2>&1
}
run_phase files
run_phase channel
adb exec-out screencap -p > "$OUT/media-channel.png"
run_phase audio
run_phase bumper
timeout 30 adb exec-out screencap -p > "$OUT/bumper.png"

# New features: captions (an .srt next to Episode 1), auto volume, closedown
# test card, picture-in-picture, sleep timer closing the app.
printf '1\n00:00:01,000 --> 00:02:00,000\nHello from the captions test\n\n2\n00:02:00,000 --> 00:06:00,000\nSecond caption line\n' > /tmp/rv-cc.srt
adb push /tmp/rv-cc.srt /data/local/tmp/rv-cc.srt >/dev/null
adb shell "cat /data/local/tmp/rv-cc.srt | run-as $PKG sh -c 'cat > cache/tv/Episode_1.srt'"
adb shell rm -f /data/local/tmp/rv-cc.srt
run_phase captions
timeout 30 adb exec-out screencap -p > "$OUT/captions.png"
run_phase closedown
sleep 11
timeout 30 adb exec-out screencap -p > "$OUT/testcard.png"
run_phase pip
adb shell input keyevent KEYCODE_HOME
sleep 4
timeout 30 adb exec-out screencap -p > "$OUT/pip.png"
timeout 20 adb shell dumpsys activity activities > "$OUT/pip-activities.txt" 2>&1
grep -iE "pinned|windowingMode=2|pip" "$OUT/pip-activities.txt" | head -20 > "$OUT/pip-summary.txt" || true
PID=$(adb shell pidof "$PKG" | tr -d '\r')
adb forward tcp:9222 "localabstract:webview_devtools_remote_$PID"
timeout 60 node scripts/media-test.mjs 9222 "$OUT/media-results.json" pip-check "${FILES[@]}" >> "$OUT/node.log" 2>&1
adb shell am force-stop "$PKG"
run_phase sleep
sleep 16
timeout 30 adb exec-out screencap -p > "$OUT/after-sleep.png"
timeout 20 adb shell dumpsys activity activities | grep -E "ResumedActivity|topResumedActivity" > "$OUT/after-sleep.txt" 2>&1
echo "pid after sleep: $(adb shell pidof "$PKG" | tr -d '\r')" >> "$OUT/after-sleep.txt"
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 8
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
adb shell settings put system user_rotation 0

# No internet at all (airplane mode), then a connection that never answers.
adb shell cmd connectivity airplane-mode enable
sleep 3
adb shell am force-stop "$PKG"
run_phase offline
adb exec-out screencap -p > "$OUT/offline.png"
adb shell cmd connectivity airplane-mode disable
sleep 5
adb shell settings put global http_proxy 10.255.255.1:3128
adb shell am force-stop "$PKG"
run_phase stalled
adb exec-out screencap -p > "$OUT/stalled.png"
adb shell settings put global http_proxy :0
adb logcat -d > "$OUT/logcat-full.txt"
grep -E "RetroVisionMedia|\[ota\]|\[native\]|FATAL|chromium.*(Media|media)" "$OUT/logcat-full.txt" | tail -60 > "$OUT/logcat-app.txt" || true
cp "$OUT/media-results.json" "$OUT/results.json" 2>/dev/null || true
