#!/usr/bin/env bash
# Decides which CI lanes a pull request can affect (ci.yml's `changes` job). Kept as a script so
# `scripts/ci/test/decide-lanes-test.sh` can prove the fail-open cases: a guard that skips the build
# because it could not work out the diff is worse than one that costs a few minutes (#490).
#
# Reads EVENT and BASE_REF, writes code=, ios=, supabase= to $GITHUB_OUTPUT.
set -uo pipefail

run_everything() {
  echo "code=true" >> "$GITHUB_OUTPUT"
  echo "ios=true" >> "$GITHUB_OUTPUT"
  echo "supabase=true" >> "$GITHUB_OUTPUT"
  exit 0
}

if [ "$EVENT" != "pull_request" ]; then
  echo "Not a pull request; running every lane."
  run_everything
fi

# Fail open. A guard that silently skips the build because it could
# not work out the diff is worse than one that costs a few minutes.
changed=$(git diff --name-only "origin/${BASE_REF}...HEAD") || {
  echo "::warning::Could not diff against origin/${BASE_REF}; running every lane."
  run_everything
}
if [ -z "$changed" ]; then
  echo "::warning::Empty diff against origin/${BASE_REF}; running every lane."
  run_everything
fi

echo "Changed files:"
echo "$changed" | sed 's/^/  /'

# Anything the shared Kotlin/Native framework or the Xcode project can
# see. `core`, `feature` and `models` are in here because :app:shared
# depends on them, so a change there does reach the iOS link. This
# file is in here too: a PR that edits CI must run the CI it edits. So is
# version-code.sh, which scripts/ios/set-version.sh calls for tagged builds.
IOS_PATHS='^(app/shared/|app/iosApp/|scripts/ios/|core/|feature/|models/|build-logic/|gradle/|config/)|\.gradle\.kts$|^gradle\.properties$|^settings\.gradle\.kts$|^\.github/workflows/ci\.yml$|^scripts/ci/decide-lanes\.sh$|^scripts/release/version-code\.sh$'
# Everything above, plus the platforms iOS cannot see but `build`,
# `desktop` and `web-tests` can.
CODE_PATHS="${IOS_PATHS}"'|^(app/androidApp/|app/desktopApp/|app/webApp/|server/|supabase/|scripts/)'

if echo "$changed" | grep -qE "$IOS_PATHS"; then ios=true; else ios=false; fi
if echo "$changed" | grep -qE "$CODE_PATHS"; then code=true; else code=false; fi
# The server: migrations, pgTAP tests, Edge Functions (#280).
SUPABASE_PATHS='^supabase/|^\.github/workflows/ci\.yml$|^scripts/ci/decide-lanes\.sh$'
if echo "$changed" | grep -qE "$SUPABASE_PATHS"; then supabase=true; else supabase=false; fi

echo "ios=$ios" >> "$GITHUB_OUTPUT"
echo "code=$code" >> "$GITHUB_OUTPUT"
echo "supabase=$supabase" >> "$GITHUB_OUTPUT"
echo "Lanes -> code=$code ios=$ios supabase=$supabase"
