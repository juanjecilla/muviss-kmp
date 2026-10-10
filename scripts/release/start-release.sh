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
# locale, seeded from the conventional commits since the last release and
# marked as a draft, plus this version's CHANGELOG.md section. The RC builds accept the draft; the PR into main does not
# (scripts/release/check-release-notes.sh --final).
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
for arg in "$@"; do
  case "$arg" in
    --hotfix) KIND=hotfix ;;
    --dry-run) DRY_RUN=1 ;;
    *) die "unknown option '$arg'" ;;
  esac
done

[ -n "$VERSION" ] || die "usage: $0 <X.Y.Z> [--hotfix] [--dry-run]"
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

# One release in flight at a time: two open release branches would each upload
# RCs to the same internal track, and "promote the latest" would pick whichever
# pushed last. Hotfixes are exempt — they exist to jump that queue.
if [ "$KIND" = release ]; then
  open="$(git ls-remote --heads origin 'release/*' | awk '{print $2}' | sed 's#refs/heads/##')"
  [ -z "$open" ] || die "a release is already in flight: $open — merge or delete it first"
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

# Seed the release notes. Only feat and fix subjects are user-facing; the rest
# (chore, docs, ci, test, refactor) would read as noise in a store listing.
range="${PREV:+$PREV..}HEAD"
changes="$(git log --no-merges --format='%s' "$range" \
  | grep -E '^(feat|fix)(\([^)]*\))?!?: ' \
  | sed -E 's/^(feat|fix)(\([^)]*\))?!?: /- /' \
  | head -12 || true)"
[ -n "$changes" ] || changes="- Bug fixes and improvements."
if [ -d fastlane/metadata/android ]; then
  for dir in fastlane/metadata/android/*/; do
    locale="$(basename "$dir")"
    notes="fastlane/release-notes/v$VERSION/$locale.txt"
    [ -f "$notes" ] && continue
    mkdir -p "$(dirname "$notes")"
    {
      echo "DRAFT: rewrite for users in $locale (max 500 chars) and delete this line before $BRANCH merges into main."
      echo "$changes"
    } >"$notes"
    # Play caps notes at 500 characters and the RC upload enforces it; drop
    # trailing items rather than fail the first RC on a long week.
    while [ "$(wc -m <"$notes" | tr -d ' ')" -gt 500 ]; do
      sed -i.bak '$d' "$notes" && rm -f "$notes.bak"
    done
  done
  git add fastlane/release-notes
  ok "seeded draft release notes in fastlane/release-notes/v$VERSION/"
fi

# CHANGELOG.md gets a section for this version from the same commits, never by
# hand (EPIC 38, #71): every feat and fix, uncapped and with its scope, since a
# changelog is for people reading the repo rather than a 500-character store
# field. Written when the branch is cut, so a fix that lands on the release
# branch afterwards is in the RC but not here; the GitHub Release's compare
# link is the exhaustive list. Only a `!` in the header (`feat(x)!:`) marks a
# breaking change; one in the subject is the subject's.
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
  -m "Seeds draft Play release notes for every listing locale and the CHANGELOG.md section (ADR 0025)."
ok "added the $VERSION section to CHANGELOG.md"

git push --quiet -u origin "$BRANCH"
ok "pushed $BRANCH — release-rc.yml is building rc1"
printf '\n  Watch it:  gh run watch\n  Next:      edit the notes, then open a PR %s → main\n\n' "$BRANCH"
