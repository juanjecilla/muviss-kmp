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

RAW_MAJOR="${BASH_REMATCH[1]}" RAW_MINOR="${BASH_REMATCH[2]}" RAW_PATCH="${BASH_REMATCH[3]}" RAW_RC="${BASH_REMATCH[5]:-0}"

# Checked as strings first: Bash arithmetic wraps silently, so a 20-digit
# field would come out small rather than fail. Leading zeros are allowed.
for field in "$RAW_MINOR" "$RAW_PATCH" "$RAW_RC"; do
  if [[ ! "$field" =~ ^0*[0-9]{1,2}$ ]]; then
    echo "version-code.sh: minor, patch and rc must be 0..99 (got '$TAG')" >&2
    exit 2
  fi
done
if [[ ! "$RAW_MAJOR" =~ ^0*[0-9]{1,4}$ ]]; then
  echo "version-code.sh: '$TAG' overflows an Int versionCode" >&2
  exit 2
fi

# 10# forces base 10, so "08" is not an invalid octal literal.
MAJOR=$((10#$RAW_MAJOR))
MINOR=$((10#$RAW_MINOR))
PATCH=$((10#$RAW_PATCH))
RC=$((10#$RAW_RC))

CODE=$((MAJOR * 1000000 + MINOR * 10000 + PATCH * 100 + RC))
if [ "$CODE" -gt 2147483647 ]; then
  echo "version-code.sh: '$TAG' overflows an Int versionCode" >&2
  exit 2
fi
echo "$CODE"
