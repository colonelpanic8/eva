#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

if (($# != 1)); then
  echo "Usage: $0 MAJOR.MINOR.PATCH" >&2
  exit 2
fi

version="${1#v}"
version_code="$(scripts/version-code.sh "$version")"
required=(
  ANDROID_KEYSTORE_FILE
  ANDROID_KEYSTORE_PASSWORD
  ANDROID_KEY_ALIAS
  ANDROID_KEY_PASSWORD
)
for variable in "${required[@]}"; do
  if [[ -z "${!variable:-}" ]]; then
    echo "Signed release builds require $variable" >&2
    exit 1
  fi
done
if [[ ! -f "$ANDROID_KEYSTORE_FILE" ]]; then
  echo "Keystore does not exist: $ANDROID_KEYSTORE_FILE" >&2
  exit 1
fi

export EVA_VERSION_NAME="$version"
export EVA_VERSION_CODE="$version_code"
./gradlew --no-daemon ktlintCheck :app:lintRelease :app:testReleaseUnitTest :app:assembleRelease

apk="app/build/outputs/apk/release/app-release.apk"
if [[ ! -f "$apk" ]]; then
  echo "Signed release APK was not produced: $apk" >&2
  exit 1
fi

if command -v apksigner >/dev/null 2>&1; then
  apksigner_bin="$(command -v apksigner)"
else
  shopt -s nullglob
  candidates=("${ANDROID_HOME:-/nonexistent}"/build-tools/*/apksigner)
  shopt -u nullglob
  if ((${#candidates[@]} == 0)); then
    echo "apksigner is required to verify the release" >&2
    exit 1
  fi
  apksigner_bin="${candidates[-1]}"
fi
"$apksigner_bin" verify --verbose --print-certs "$apk"

mkdir -p dist
cp "$apk" dist/eva.apk
echo "Built dist/eva.apk ($version, versionCode $version_code)"
