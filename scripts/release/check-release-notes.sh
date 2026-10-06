#!/bin/bash
# Checks Play release notes for one version: one file per listing locale at
# fastlane/release-notes/v<version>/<locale>.txt, within Play's 500 characters.
# With --final, also refuses the draft marker the release train seeds them with
# (start-release.sh): merging into main promotes to production with these notes.
#
# Usage: scripts/release/check-release-notes.sh 1.2.0 [--final]
set -euo pipefail
cd "$(dirname "$0")/../.." || exit 1

VERSION="${1:?usage: $0 <X.Y.Z> [--final]}"
FINAL="${2:-}"
DRAFT_MARKER="DRAFT:"

[ -d fastlane/metadata/android ] || { echo "no Play listing; nothing to check"; exit 0; }

status=0
for dir in fastlane/metadata/android/*/; do
  locale="$(basename "$dir")"
  notes="fastlane/release-notes/v$VERSION/$locale.txt"
  if [ ! -f "$notes" ]; then
    echo "::error file=$notes::missing (Play release notes for $locale)"
    status=1
    continue
  fi
  chars="$(wc -m <"$notes" | tr -d ' ')"
  if [ "$chars" -gt 500 ]; then
    echo "::error file=$notes::$chars characters; Play allows 500"
    status=1
  fi
  if [ "$FINAL" = "--final" ] && grep -q "^$DRAFT_MARKER" "$notes"; then
    echo "::error file=$notes::still a draft — rewrite it for users and delete the '$DRAFT_MARKER' line"
    status=1
  fi
done
[ "$status" -eq 0 ] && echo "ok: release notes for v$VERSION"
exit "$status"
