#!/usr/bin/env bash
set -euo pipefail

trap 'adb logcat -b crash -d > "$RUNNER_TEMP/release-smoke-crashes.txt"' EXIT
adb install "$RUNNER_TEMP/release-smoke.apk"
adb logcat -b crash -c
adb shell am start -W -n com.nsheremet.filmlab/com.example.MainActivity
for _ in 1 2 3 4 5; do
  if adb shell uiautomator dump /sdcard/release-smoke.xml \
    && adb pull /sdcard/release-smoke.xml "$RUNNER_TEMP/release-smoke.xml" \
    && grep -Fq 'text="Choose photo"' "$RUNNER_TEMP/release-smoke.xml"; then break; fi
  sleep 1
done
grep -Fq 'text="Choose photo"' "$RUNNER_TEMP/release-smoke.xml"
adb shell pidof com.nsheremet.filmlab
adb logcat -b crash -d > "$RUNNER_TEMP/release-smoke-crashes.txt"
if grep -Fq 'com.nsheremet.filmlab' "$RUNNER_TEMP/release-smoke-crashes.txt"; then
  cat "$RUNNER_TEMP/release-smoke-crashes.txt"
  exit 1
fi
