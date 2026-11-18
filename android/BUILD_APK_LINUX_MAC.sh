#!/usr/bin/env bash
set -euo pipefail

if ! command -v gradle >/dev/null 2>&1; then
  echo "Gradle is not installed. Install Android Studio/Android SDK and Gradle 8.9 first." >&2
  exit 1
fi
if [[ -z "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ]]; then
  echo "ANDROID_HOME or ANDROID_SDK_ROOT is not set. Install Android SDK Platform 35." >&2
  exit 1
fi

gradle --no-daemon assembleDebug
APK="app/build/outputs/apk/debug/app-debug.apk"
test -s "$APK"
echo "APK built successfully: $APK"
