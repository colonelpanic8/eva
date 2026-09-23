set shell := ["bash", "-euo", "pipefail", "-c"]

default:
    @just --list

# Generate repository, F-Droid, and Android icons from the canonical SVG.
icons:
    python3 scripts/generate-icons.py

# Run formatting checks, Android lint, unit tests, and a debug build.
check:
    ./gradlew --no-daemon ktlintCheck :app:lintDebug :app:testDebugUnitTest :app:assembleDebug

# Apply Kotlin formatting.
format:
    ./gradlew --no-daemon ktlintFormat

# Check Kotlin formatting without changing files.
format-check:
    ./gradlew --no-daemon ktlintCheck

# Run Android lint with warnings treated as errors.
lint:
    ./gradlew --no-daemon :app:lintDebug

# Run local JVM tests.
test:
    ./gradlew --no-daemon :app:testDebugUnitTest

# Build the debug APK.
build:
    ./gradlew --no-daemon :app:assembleDebug

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

# Replace the shipped prompt with the instruction catalog's current eva-prompt.yaml.
prompt-sync:
    curl -fsSL https://raw.githubusercontent.com/colonelpanic8/eva-instructions/main/eva-prompt.yaml -o app/src/main/resources/eva-prompt.yaml

# Build the self-hosted F-Droid repository; see docs/operations.md.
fdroid-repo:
    ./scripts/fdroid/build-repo.sh
