#!/usr/bin/env bash
# Builds CallAudioProbe into a dex and runs it as shell over adb.
# Usage: ANDROID_SERIAL=<serial> ./probe.sh check | listen 10 /data/local/tmp/down.wav | tone 5 | both 10 /data/local/tmp/down.wav
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
sdk="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
platform="$(ls -d "$sdk"/platforms/android-* | sort -V | tail -1)"
d8="$(ls -d "$sdk"/build-tools/* | sort -V | tail -1)/d8"
out="$here/build"
mkdir -p "$out/classes"
javac -nowarn --release 17 -cp "$platform/android.jar" -d "$out/classes" "$here/src/CallAudioProbe.java"
"$d8" --min-api 33 --lib "$platform/android.jar" --output "$out" $(find "$out/classes" -name '*.class')
adb push "$out/classes.dex" /data/local/tmp/call-audio-probe.dex >/dev/null
exec adb shell CLASSPATH=/data/local/tmp/call-audio-probe.dex app_process / CallAudioProbe "$@"
