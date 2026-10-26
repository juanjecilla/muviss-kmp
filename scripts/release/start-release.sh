#!/bin/bash
# Start a release or hotfix branch (ADR 0025). Pushing the branch is the whole
# trigger: .github/workflows/release-rc.yml then builds a signed AAB, tags it
# vX.Y.Z-rcN and uploads it to Play's internal track, and does so again on
# every later push to the branch.
#
#   release/X.Y.Z  is cut from origin/develop — by hand for 1.0.0, then by the
#                  Monday release train (.github/workflows/release-train.yml).
#   hotfix/X.Y.Z   is cut from origin/main (--hotfix).
#
# Either one reaches production by a PR into main; merging it tags vX.Y.Z and
# promotes the last RC (release.yml).
#
# The branch starts with one commit: Play release notes for every listing
# locale, generated from the conventional commits since the last release
# (scripts/release/release-notes.sh), plus this version's CHANGELOG.md section.
# Nobody has to edit the notes; a person may, and the train then keeps their text.
#
# Usage:  scripts/release/start-release.sh 1.2.0 [--hotfix] [--dry-run]
set -euo pipefail
cd "$(dirname "$0")/../.." || exit 1

die() { printf '\033[31merror\033[0m  %s\n' "$*" >&2; exit 1; }
note() { printf '\033[33m·\033[0m %s\n' "$*"; }
ok() { printf '\033[32m✓\033[0m %s\n' "$*"; }

VERSION="${1:-}"
shift || true
KIND=release
DRY_RUN=
STAGGER_CHECKED=
for arg in "$@"; do
  case "$arg" in
    --hotfix) KIND=hotfix ;;
    --dry-run) DRY_RUN=1 ;;
    # The train has applied ADR 0030's stagger rule (release_stages.rb plan),
    # so another open release is allowed as long as it is past internal.
    --stagger-checked) STAGGER_CHECKED=1 ;;
    *) die "unknown option '$arg'" ;;
  esac
done

[ -n "$VERSION" ] || die "usage: $0 <X.Y.Z> [--hotfix] [--dry-run] [--stagger-checked]"
# Plain X.Y.Z: the tags derived from it (vX.Y.Z-rcN, vX.Y.Z) must keep
# matching app/androidApp/build.gradle.kts's versionName pattern.
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] \
  || die "version must be X.Y.Z (no leading v, no suffix); got '$VERSION'"

BRANCH="$KIND/$VERSION"
if [ "$KIND" = hotfix ]; then BASE=main; else BASE=develop; fi

[ -z "$(git status --porcelain)" ] || die "working tree is dirty; commit or stash first"

git fetch --quiet --tags origin
git rev-parse -q --verify "origin/$BASE" >/dev/null || die "origin/$BASE does not exist"

git ls-remote --exit-code --heads origin "$BRANCH" >/dev/null 2>&1 \
  && die "$BRANCH already exists on origin"
git rev-parse -q --verify "refs/heads/$BRANCH" >/dev/null \
  && die "$BRANCH already exists locally"
git rev-parse -q --verify "refs/tags/v$VERSION" >/dev/null \
  && die "tag v$VERSION already exists"

# A version still in internal blocks the next cut (ADR 0030): two RCs on the
# internal track would replace each other, and "promote the latest" would pick
# whichever pushed last. Versions past internal (beta, production) are fine. The
# train checks the stage itself and passes --stagger-checked; a hand cut without
# it refuses while any release branch is open, the conservative reading.
# Hotfixes are exempt — they exist to jump that queue.
if [ "$KIND" = release ] && [ -z "$STAGGER_CHECKED" ]; then
  open="$(git ls-remote --heads origin 'release/*' | awk '{print $2}' | sed 's#refs/heads/##')"
  [ -z "$open" ] || die "a release is already open: $open — run it through the train (it checks the stage), or pass --stagger-checked once you have checked that none is still in internal"
fi

# The Play Console rejects a versionName it has already seen, after the build.
PREV="$(git tag -l 'v[0-9]*' | grep -v -- '-' | sort -V | tail -1 || true)"
if [ -n "$PREV" ]; then
  OLDEST="$(printf '%s\n%s\n' "${PREV#v}" "$VERSION" | sort -V | head -1)"
  [ "$OLDEST" != "$VERSION" ] || die "$VERSION is not newer than the last release $PREV"
  note "last release: $PREV"
else
  note "no release tag yet — this is the first"
fi

if [ -d fastlane/metadata/android ]; then
  scripts/store/check-listing.sh >/dev/null || die "the Play listing is over a field limit (run scripts/store/check-listing.sh)"
fi

note "branch:  $BRANCH from origin/$BASE @ $(git rev-parse --short "origin/$BASE")"
if [ -n "$DRY_RUN" ]; then
  ok "dry run — nothing was created or pushed"
  exit 0
fi

git switch --quiet -c "$BRANCH" "origin/$BASE"

# The release notes: final, user-facing, for every listing locale. They never
# hold a release; release-rc.yml rewrites them as fixes land on the branch.
if [ -d fastlane/metadata/android ]; then
  scripts/release/release-notes.sh "$VERSION" --write >/dev/null
  git add fastlane/release-notes
  ok "wrote release notes in fastlane/release-notes/v$VERSION/"
fi

# CHANGELOG.md gets a section for this version from the same commits, never by
# hand (EPIC 38, #71): every feat and fix, uncapped and with its scope, since a
# changelog is for people reading the repo rather than a 500-character store
# field. Written when the branch is cut, so a fix that lands on the release
# branch afterwards is in the RC but not here; the GitHub Release's compare
# link is the exhaustive list. Only a `!` in the header (`feat(x)!:`) marks a
# breaking change; one in the subject is the subject's.
range="${PREV:+$PREV..}HEAD"
changelog_items() { # <type>
  git log --no-merges --format='%s' "$range" \
    | grep -E "^$1(\([^)]*\))?!?: " \
    | sed -E "s/^$1\(([^)]*)\)!: (.*)$/- **\1**: \2 (breaking)/; s/^$1\(([^)]*)\): (.*)$/- **\1**: \2/; s/^$1!: (.*)$/- \1 (breaking)/; s/^$1: (.*)$/- \1/" || true
}
features="$(changelog_items feat)"
fixes="$(changelog_items fix)"
section="$(
  echo "## $VERSION — $(date -u +%Y-%m-%d)"
  [ -n "$features" ] && printf '\n### Features\n\n%s\n' "$features"
  [ -n "$fixes" ] && printf '\n### Fixes\n\n%s\n' "$fixes"
  [ -n "$features$fixes" ] || printf '\nMaintenance only: no user-facing changes.\n'
)"
if [ ! -f CHANGELOG.md ]; then
  printf '# Changelog\n\nGenerated by scripts/release/start-release.sh from conventional commit\ntitles (feat and fix only) when each release branch is cut. Do not edit by\nhand; reword a release'"'"'s user-facing notes in fastlane/release-notes/ instead.\n' >CHANGELOG.md
fi
# The new section goes above the newest existing one, under the header.
# Through the environment: BSD awk (macOS) refuses a newline in a -v value.
SECTION="$section" awk '
  !done && /^## / { print ENVIRON["SECTION"]; print ""; done = 1 }
  { print }
  END { if (!done) { print ""; print ENVIRON["SECTION"] } }
' CHANGELOG.md >CHANGELOG.md.tmp
mv CHANGELOG.md.tmp CHANGELOG.md
git add CHANGELOG.md
git commit --quiet -m "chore(release): start $VERSION" \
  -m "Writes the Play release notes for every listing locale and the CHANGELOG.md section (ADR 0025)."
ok "added the $VERSION section to CHANGELOG.md"

git push --quiet -u origin "$BRANCH"
ok "pushed $BRANCH — release-rc.yml is building rc1"
printf '\n  Watch it:  gh run watch\n  Next:      the Sunday step promotes %s (docs/RELEASING.md, staged releases)\n\n' "$BRANCH"
