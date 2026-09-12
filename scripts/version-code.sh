#!/usr/bin/env bash
set -euo pipefail

version="${1#v}"
if [[ ! "$version" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
  echo "Expected a MAJOR.MINOR.PATCH version, got: $1" >&2
  exit 2
fi

major="${BASH_REMATCH[1]}"
minor="${BASH_REMATCH[2]}"
patch="${BASH_REMATCH[3]}"
if ((minor > 999 || patch > 999)); then
  echo "Minor and patch components must be at most 999" >&2
  exit 2
fi

code=$((10#$major * 1000000 + 10#$minor * 1000 + 10#$patch))
if ((code < 1 || code > 2100000000)); then
  echo "Computed Android version code is outside the supported range: $code" >&2
  exit 2
fi

printf '%s\n' "$code"
