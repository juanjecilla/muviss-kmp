#!/bin/bash
# PR guard (ADR 0025, .github/workflows/pr-guards.yml): a `feat` PR into develop
# that changes a feature or app module must also change website/, or carry the
# `no-landing` label. The landing deploys from `main` only, so copy written on
# develop goes live exactly when the feature ships.
#
# A `feat` that takes the `no-landing` label has nothing user-visible for the
# landing, so it must not reach the release notes either: it also needs the
# `no-notes` label, which release-notes.sh reads by PR number (#572). Only a PR
# that changes a feature or app module is asked, the paths this guard covers.
#
# Inputs (env): TITLE, LABELS (comma-separated), BASE_SHA, HEAD_SHA.
# Run locally: TITLE='feat(x): y' LABELS= BASE_SHA=origin/develop HEAD_SHA=HEAD scripts/release/landing-guard.sh
set -euo pipefail

: "${TITLE:?}" "${BASE_SHA:?}" "${HEAD_SHA:?}"
LABELS="${LABELS:-}"

if ! [[ "$TITLE" =~ ^feat(\([^\)]*\))?!?: ]]; then
  echo "ok: not a feat PR ('$TITLE')"
  exit 0
fi

changed="$(git diff --name-only "$BASE_SHA...$HEAD_SHA")"

if [[ ",$LABELS," == *",no-landing,"* ]]; then
  if grep -qE '^(feature|app)/' <<<"$changed" && [[ ",$LABELS," != *",no-notes,"* ]]; then
    cat <<'MSG'
::error::This feat PR is labelled no-landing, so it has nothing for the landing, and it must not reach the release notes either.
Add the `no-notes` label too: release-notes.sh leaves a labelled PR out of the notes (AGENTS.md, #572).
MSG
    exit 1
  fi
  echo "ok: labelled no-landing"
  exit 0
fi

if ! grep -qE '^(feature|app)/' <<<"$changed"; then
  echo "ok: no feature/ or app/ change"
  exit 0
fi
if grep -qE '^website/' <<<"$changed"; then
  echo "ok: website/ is updated too"
  exit 0
fi

cat <<'MSG'
::error::This feat PR changes the app but not the landing.
Every free, user-visible feature goes on muvissapp.com in the same PR:
  - copy: website/src/i18n/en.ts and es.ts (feature grid or a spotlight)
  - the Play listing too, if it changes what the app does: fastlane/metadata/android/*/full_description.txt
Paid features (sync, co-watch) never appear on the landing. If this feat is
paid, invisible or not user-facing, add the `no-landing` label instead.
MSG
exit 1
