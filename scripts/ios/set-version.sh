#!/bin/bash
# Stamps a built Info.plist with the version the Android build derives from git
# (ADR 0030; docs/RELEASING.md item 3). Run from a "Set version from git" Run
# Script phase in BOTH app/iosApp targets, iosApp and MuvissWidget: App Store
# Connect rejects an upload whose app and embedded extension disagree
# (ITMS-90473), so both must come from this one script on the same git state.
#
#   scripts/ios/set-version.sh "$TARGET_BUILD_DIR/$INFOPLIST_PATH"
#
# CFBundleShortVersionString is numeric X.Y.Z only: the tag's version with any
# -rcN dropped, as Android's versionName is. App Store Connect rejects a
# suffix, so an untagged build reads 0.1.0 (Android's readable dev label
# cannot be uploaded anyway).
#
# CFBundleVersion is the Android versionCode (MuvissVersion.codeFor): the
# scripts/release/version-code.sh value on an exact vX.Y.Z[-rcN] tag, else the
# commit count. So an RC's build number carries its rc, and the same tagged
# commit gives the same number on both platforms.
#
# A final tag's number (rc 0) is below its RCs', and a commit after it falls
# back to the commit count. Neither is ever uploaded: only RCs go to
# TestFlight, and production promotes the RC binary without rebuilding
# (ADR 0030, ADR 0033), so within one version the uploads only ever rise.
#
# Without git it keeps the Config.xcconfig values and succeeds: a source-only
# archive must still build.
#
# MUVISS_REPO_ROOT overrides the repository root; the tests use it.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="${MUVISS_REPO_ROOT:-$(cd "$HERE/../.." && pwd)}"
PLIST="${1:-}"

if [ -z "$PLIST" ]; then
  echo "usage: set-version.sh <built Info.plist>" >&2
  exit 2
fi

# Only ROOT's own history counts: a source-only copy unpacked inside some other
# repository must keep the fallback, not stamp that repository's count or tag.
TOP="$(git -C "$ROOT" rev-parse --show-toplevel 2>/dev/null || true)"
if [ -n "$TOP" ] && [ "$(cd "$TOP" && pwd -P)" = "$(cd "$ROOT" && pwd -P)" ]; then
  COUNT="$(git -C "$ROOT" rev-list --count HEAD 2>/dev/null || true)"
else
  COUNT=""
fi
if [ -z "$COUNT" ]; then
  echo "warning: no git history for $ROOT, keeping CURRENT_PROJECT_VERSION/MARKETING_VERSION from Config.xcconfig"
  exit 0
fi

# --dirty: a modified tree is not a release tag, same as MuvissVersion.
EXACT_TAG="$(git -C "$ROOT" describe --tags --exact-match --dirty --match 'v*' 2>/dev/null || true)"
BUILD="$("$ROOT/scripts/release/version-code.sh" "$EXACT_TAG" 2>/dev/null)" || BUILD="$COUNT"

DESCRIBE="$(git -C "$ROOT" describe --tags --always --dirty 2>/dev/null || true)"
if [[ "$DESCRIBE" =~ ^v?([0-9]+\.[0-9]+\.[0-9]+) ]]; then
  NAME="${BASH_REMATCH[1]}"
else
  NAME="0.1.0"
fi

if [ ! -f "$PLIST" ]; then
  echo "note: $PLIST is not built yet, nothing to stamp"
  exit 0
fi

set_key() {
  /usr/libexec/PlistBuddy -c "Set :$1 $2" "$PLIST" 2>/dev/null \
    || /usr/libexec/PlistBuddy -c "Add :$1 string $2" "$PLIST"
}
set_key CFBundleShortVersionString "$NAME" || exit 1
set_key CFBundleVersion "$BUILD" || exit 1
echo "stamped $(basename "$PLIST"): CFBundleShortVersionString=$NAME CFBundleVersion=$BUILD"
