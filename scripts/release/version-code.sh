#!/bin/bash
# Prints the Android versionCode of a release tag (ADR 0030):
#
#   major × 1 000 000 + minor × 10 000 + patch × 100 + rcN
#
# so 1.1.0-rc4 is 1010004, and a final tag (no rcN) is rc 0. This must equal
# MuvissVersion.releaseCode in build-logic (the Gradle side, tested there); the
# workflows and fastlane call this script, and the tests pin both to the same
# vectors. Exit 2 for anything that is not vX.Y.Z or vX.Y.Z-rcN, or a field
# above 99, which the formula cannot hold without colliding.
#
# Usage: scripts/release/version-code.sh v1.1.0-rc4
set -euo pipefail

TAG="${1:-}"
if [[ ! "$TAG" =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+)(-rc([0-9]+))?$ ]]; then
  echo "version-code.sh: '$TAG' is not vX.Y.Z or vX.Y.Z-rcN" >&2
  exit 2
fi

# 10# forces base 10, so "08" is not an invalid octal literal.
MAJOR=$((10#${BASH_REMATCH[1]}))
MINOR=$((10#${BASH_REMATCH[2]}))
PATCH=$((10#${BASH_REMATCH[3]}))
RC=$((10#${BASH_REMATCH[5]:-0}))

for field in "$MINOR" "$PATCH" "$RC"; do
  if [ "$field" -gt 99 ]; then
    echo "version-code.sh: minor, patch and rc must be 0..99 (got '$TAG')" >&2
    exit 2
  fi
done

CODE=$((MAJOR * 1000000 + MINOR * 10000 + PATCH * 100 + RC))
if [ "$CODE" -gt 2147483647 ]; then
  echo "version-code.sh: '$TAG' overflows an Int versionCode" >&2
  exit 2
fi
echo "$CODE"
