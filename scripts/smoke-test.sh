#!/usr/bin/env bash
# Installs the APK on a running emulator, launches it twice and captures what happened.
# Usage: bash scripts/smoke-test.sh RetroVision.apk out-dir
set -u
APK="$1"
OUT="${2:-smoke}"
PKG=com.retrovision.player
mkdir -p "$OUT"

adb install -r "$APK"
adb shell dumpsys package "$PKG" | grep -E "versionCode|versionName" > "$OUT/package.txt" || true
adb logcat -c

echo "First launch"
adb shell am start -W -n "$PKG/.MainActivity"
sleep 35
adb exec-out screencap -p > "$OUT/1-first-launch.png"

echo "Background, then relaunch (a downloaded update takes over here)"
adb shell input keyevent KEYCODE_HOME
sleep 10
adb shell am start -W -n "$PKG/.MainActivity"
sleep 30
adb exec-out screencap -p > "$OUT/2-relaunch.png"

echo "Landscape"
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
sleep 10
adb exec-out screencap -p > "$OUT/3-landscape.png"

adb logcat -d > "$OUT/logcat-full.txt"
grep -E "\[ota\]|\[native\]|Capacitor|CapacitorUpdater|RetroVision|chromium|AndroidRuntime|FATAL" "$OUT/logcat-full.txt" > "$OUT/logcat-app.txt" || true
echo "--- app log ---"
grep -E "\[ota\]|\[native\]|FATAL|AndroidRuntime" "$OUT/logcat-full.txt" | tail -40 || true

if grep -q "FATAL EXCEPTION" "$OUT/logcat-full.txt"; then
  echo "The app crashed, see $OUT/logcat-full.txt"
  exit 1
fi
