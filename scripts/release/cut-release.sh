#!/bin/bash
# Cut a release: check the tree is releasable, tag it, push the tag.
#
# Pushing the tag is the whole trigger — `.github/workflows/release.yml` runs on
# `v*` and builds the signed AAB and the three desktop installers, then attaches
# them to a GitHub Release. Nothing else here talks to CI.
#
# This exists because the checks below are the ones easy to skip by hand, and
# each has a specific failure it prevents:
#
#   - a tag on the wrong branch or a stale checkout ships code nobody reviewed;
#   - a dirty tree ships something that is in no commit at all;
#   - a version that goes backwards is rejected by the Play Console *after* the
#     build, not before it;
#   - a tag that already exists silently does nothing when pushed.
#
# Usage:  scripts/release/cut-release.sh 1.2.0
#         scripts/release/cut-release.sh 1.2.0 --dry-run
set -euo pipefail
cd "$(dirname "$0")/../.." || exit 1

VERSION="${1:-}"
DRY_RUN="${2:-}"

die() { printf '\033[31merror\033[0m  %s\n' "$*" >&2; exit 1; }
note() { printf '\033[33m·\033[0m %s\n' "$*"; }
ok() { printf '\033[32m✓\033[0m %s\n' "$*"; }

[ -n "$VERSION" ] || die "usage: $0 <X.Y.Z> [--dry-run]"

# Plain X.Y.Z. The `v` is added here so the tag and the argument cannot drift,
# and so `git describe` keeps matching app/androidApp/build.gradle.kts's
# `^v?(\d+\.\d+\.\d+)...` pattern, which is what becomes versionName.
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] \
  || die "version must be X.Y.Z (no leading v, no suffix); got '$VERSION'"
TAG="v$VERSION"

BRANCH="$(git rev-parse --abbrev-ref HEAD)"
[ "$BRANCH" = "main" ] || die "releases are cut from main; you are on '$BRANCH'"

[ -z "$(git status --porcelain)" ] \
  || die "working tree is dirty; commit or stash first (a tag would not include these changes)"

git fetch --tags --quiet origin
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] \
  || die "local main is not identical to origin/main; pull or push first"

git rev-parse -q --verify "refs/tags/$TAG" >/dev/null \
  && die "tag $TAG already exists locally"
git ls-remote --exit-code --tags origin "refs/tags/$TAG" >/dev/null 2>&1 \
  && die "tag $TAG already exists on origin"

# versionCode is the commit count and only ever rises, so it needs no check.
# versionName comes from the tag, and the Play Console rejects a version it has
# already seen — cheaper to catch here than after a signed build.
PREV="$(git describe --tags --abbrev=0 2>/dev/null || true)"
if [ -n "$PREV" ]; then
  # `sort -V` orders versions properly (1.10.0 after 1.9.0, which a string
  # compare gets wrong). If the new version sorts first, it is the older one.
  OLDEST="$(printf '%s\n%s\n' "${PREV#v}" "$VERSION" | sort -V | head -1)"
  if [ "$OLDEST" = "$VERSION" ]; then
    die "$VERSION is not newer than the previous tag $PREV"
  fi
  note "previous release: $PREV"
else
  note "no previous tag — this is the first release this repo has ever cut"
fi

# Play shows per-release notes from fastlane/release-notes/<tag>/<locale>.txt,
# one per listing locale (ADR 0024). The upload lane fails without them, but
# only after a full signed build — catch it here instead.
if [ -d fastlane/metadata/android ]; then
  for dir in fastlane/metadata/android/*/; do
    locale="$(basename "$dir")"
    notes="fastlane/release-notes/$TAG/$locale.txt"
    [ -f "$notes" ] || die "missing $notes (Play release notes, max 500 chars); add it in a PR first"
    [ "$(wc -m < "$notes")" -le 500 ] || die "$notes is over Play's 500-character limit"
  done
  ok "release notes present for every listing locale"
fi

note "branch:       $BRANCH @ $(git rev-parse --short HEAD)"
note "tag:          $TAG"
note "versionCode:  $(git rev-list --count HEAD)"

# The tag is annotated, not lightweight: `git describe` prefers annotated tags,
# and the tagger date is what dates the release.
if [ "$DRY_RUN" = "--dry-run" ]; then
  ok "dry run — nothing was tagged or pushed"
  exit 0
fi

git tag -a "$TAG" -m "Muviss $VERSION"
ok "tagged $TAG"

git push origin "$TAG"
ok "pushed $TAG — the Release workflow is now building"
printf '\n  Watch it:   gh run watch\n  Release:    gh release view %s --web\n\n' "$TAG"

cat <<'REMAINING'
Still manual after this (docs/RELEASING.md):
  - the AAB lands on Play's internal track only; promote with the
    "Play promote" workflow (gh workflow run play-promote.yml)
  - the desktop installers are unsigned/un-notarized (#39, #179)
  - iOS/TestFlight is not built by CI yet (#78)
REMAINING
