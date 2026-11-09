#!/usr/bin/env bash
# R8 left a mapping and no missing rules (#486). The mapping is what makes a
# production stack trace readable; missing_rules.txt is R8 saying a keep rule is
# absent for a class something still reaches.
#
#   scripts/ci/check-r8-output.sh [MAPPING_DIR]   default app/androidApp/build/outputs/mapping/release
set -euo pipefail
dir="${1:-app/androidApp/build/outputs/mapping/release}"

if [ ! -s "$dir/mapping.txt" ]; then
  echo "::error::R8 left no mapping.txt in $dir (missing or empty); traces would ship obfuscated"
  exit 1
fi
if [ -e "$dir/missing_rules.txt" ]; then
  cat "$dir/missing_rules.txt"
  echo "::error::R8 reported missing keep rules (above)"
  exit 1
fi
echo "R8 mapping present, no missing rules"
