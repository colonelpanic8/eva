set shell := ["bash", "-euo", "pipefail", "-c"]
set positional-arguments

default:
    @just --list

# Generate repository, F-Droid, and Android icons from the canonical SVG.
icons:
    python3 scripts/generate-icons.py

# Run formatting checks, Android lint, unit tests, and a debug build.
check:
    ./gradlew --no-daemon ktlintCheck :app:lintDebug :wear:lintDebug :diagnostics-core:test :wear-core:test :eva-core:test :eva-desktop:test :device-control-core:test :device-control-portal:test :device-control-host:test :app:testDebugUnitTest :wear:testDebugUnitTest :app:assembleDebug :wear:assembleDebug

# Apply Kotlin formatting.
format:
    ./gradlew --no-daemon ktlintFormat

# Check Kotlin formatting without changing files.
format-check:
    ./gradlew --no-daemon ktlintCheck

# Run Android lint with warnings treated as errors.
lint:
    ./gradlew --no-daemon :app:lintDebug :wear:lintDebug

# Run local JVM tests.
test:
    ./gradlew --no-daemon :diagnostics-core:test :wear-core:test :eva-core:test :eva-desktop:test :device-control-core:test :device-control-portal:test :device-control-host:test :app:testDebugUnitTest :wear:testDebugUnitTest

# Build the debug APK.
build:
    ./gradlew --no-daemon :app:assembleDebug :wear:assembleDebug

# Install the debug APK on the selected adb device.
install:
    adb install -r app/build/outputs/apk/debug/app-debug.apk

# Build a fail-closed signed release for MAJOR.MINOR.PATCH.
release version:
    ./scripts/build-release.sh {{version}}

# Compute the Android version code for MAJOR.MINOR.PATCH.
version-code version:
    ./scripts/version-code.sh {{version}}

# Regenerate fastlane changelogs; pass --check for CI validation.
fdroid-changelogs *args:
    ./scripts/fdroid/changelogs.py "$@"

# Replace the shipped prompt and tool wording with the instruction catalog's current files.
prompt-sync catalog="":
    for file in eva-prompt.yaml eva-wording.yaml; do if [ -n "$1" ]; then cp "$1/$file" "eva-core/src/main/resources/$file"; else curl -fsSL "https://raw.githubusercontent.com/colonelpanic8/eva-instructions/main/$file" -o "eva-core/src/main/resources/$file"; fi; done

# Build the self-hosted F-Droid repository; see docs/operations.md.
fdroid-repo:
    ./scripts/fdroid/build-repo.sh

# Build the desktop EVA command (eva-desktop/build/install/eva-desktop/bin/eva-desktop).
desktop:
    ./gradlew --no-daemon :eva-desktop:installDist

# Build and run the desktop EVA with the native libraries its window needs, e.g. `just desktop-run tray`.
desktop-run *args: desktop
    LD_LIBRARY_PATH="$EVA_DESKTOP_LIBRARY_PATH" eva-desktop/build/install/eva-desktop/bin/eva-desktop {{args}}

# Build the standalone JVM device-control CLI.
device-host:
    ./gradlew --no-daemon :device-control-host:installDist

# Install the watch debug app without removing data.
wear-install device:
    adb -s {{device}} install -r wear/build/outputs/apk/debug/wear-debug.apk
