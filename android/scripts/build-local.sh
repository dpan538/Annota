#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Set JAVA_HOME to a full JDK 17 or 21 (a JRE is insufficient)}"
: "${ANDROID_HOME:?Set ANDROID_HOME to Android SDK with platform 36 and build-tools 35.0.0}"
./gradlew assembleDebug assembleDebugAndroidTest lintDebug "$@"
