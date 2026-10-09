#!/bin/bash
# Tests for the release scripts (ADR 0025), against throwaway git repos.
#   scripts/release/test/release-scripts-test.sh
set -uo pipefail
SCRIPTS="$(cd "$(dirname "$0")/.." && pwd)"
REPO_ROOT="$(cd "$SCRIPTS/../.." && pwd)"
failures=0
pass() { printf '  \033[32mok\033[0m   %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; failures=$((failures + 1)); }
expect_eq() { [ "$2" = "$3" ] && pass "$1" || fail "$1: expected '$3', got '$2'"; }

new_repo() {
  local dir
  dir="$(mktemp -d)"
  git -C "$dir" init -q -b develop
  git -C "$dir" config user.email t@example.com
  git -C "$dir" config user.name test
  git -C "$dir" config commit.gpgsign false
  git -C "$dir" config tag.gpgsign false
  echo "$dir"
}
commit() { git -C "$1" commit -q --allow-empty -m "$2"; }

echo "next-version.sh"
r="$(new_repo)"; commit "$r" "chore: init"
expect_eq "first release is 1.0.0" "$(cd "$r" && "$SCRIPTS/next-version.sh" develop)" "1.0.0"
git -C "$r" tag -a v1.0.0 -m v1.0.0
(cd "$r" && "$SCRIPTS/next-version.sh" develop >/dev/null 2>&1); expect_eq "nothing new exits 3" "$?" "3"
commit "$r" "fix(search): x"
expect_eq "fix bumps patch" "$(cd "$r" && "$SCRIPTS/next-version.sh" develop)" "1.0.1"
commit "$r" "feat(triage): y"
expect_eq "feat bumps minor" "$(cd "$r" && "$SCRIPTS/next-version.sh" develop)" "1.1.0"
git -C "$r" tag -a v1.0.1-rc1 -m rc
expect_eq "rc tags are not releases" "$(cd "$r" && "$SCRIPTS/next-version.sh" develop)" "1.1.0"
commit "$r" "refactor(db)!: z"
expect_eq "bang bumps major" "$(cd "$r" && "$SCRIPTS/next-version.sh" develop)" "2.0.0"
r2="$(new_repo)"; commit "$r2" "chore: init"; git -C "$r2" tag -a v1.2.3 -m t
git -C "$r2" commit -q --allow-empty -m "fix: a" -m "BREAKING CHANGE: b"
expect_eq "BREAKING CHANGE footer bumps major" "$(cd "$r2" && "$SCRIPTS/next-version.sh" develop)" "2.0.0"
# A merge commit alone (the back-merge) is not a change.
r3="$(new_repo)"; commit "$r3" "chore: init"; git -C "$r3" tag -a v1.0.0 -m t
git -C "$r3" switch -q -c side; commit "$r3" "fix: on side"; git -C "$r3" tag -a v1.0.1 -m t
git -C "$r3" switch -q develop; git -C "$r3" merge -q --no-ff side -m "Merge back-merge/v1.0.1"
(cd "$r3" && "$SCRIPTS/next-version.sh" develop >/dev/null 2>&1); expect_eq "back-merge alone exits 3" "$?" "3"
# The newest tag counts even when develop cannot reach it yet.
r4="$(new_repo)"; commit "$r4" "chore: init"; git -C "$r4" tag -a v1.0.0 -m t
git -C "$r4" switch -q -c main; commit "$r4" "fix: release fix"; git -C "$r4" tag -a v1.1.0 -m t
git -C "$r4" switch -q develop; commit "$r4" "fix: after"
expect_eq "unreachable newest tag still bases the bump" "$(cd "$r4" && "$SCRIPTS/next-version.sh" develop)" "1.1.1"

echo "landing-guard.sh"
r="$(new_repo)"; commit "$r" "chore: init"; base="$(git -C "$r" rev-parse HEAD)"
mkdir -p "$r/feature/x"; echo a >"$r/feature/x/A.kt"; git -C "$r" add -A; commit "$r" "feat: a"
guard() { (cd "$r" && TITLE="$1" LABELS="$2" BASE_SHA="$base" HEAD_SHA=HEAD "$SCRIPTS/landing-guard.sh" >/dev/null 2>&1); echo $?; }
expect_eq "feat touching feature/ only fails" "$(guard 'feat(x): new' '')" "1"
expect_eq "no-landing label passes" "$(guard 'feat(x): new' 'bug,no-landing')" "0"
expect_eq "fix PR passes" "$(guard 'fix(x): new' '')" "0"
mkdir -p "$r/website/src"; echo b >"$r/website/src/b.ts"; git -C "$r" add -A; commit "$r" "feat: site"
expect_eq "feat touching website/ too passes" "$(guard 'feat(x): new' '')" "0"

echo "check-release-notes.sh"
tmp="$(mktemp -d)"; mkdir -p "$tmp/scripts/release" "$tmp/fastlane/metadata/android/en-US" "$tmp/fastlane/metadata/android/es-ES" "$tmp/fastlane/release-notes/v1.0.0"
cp "$SCRIPTS/check-release-notes.sh" "$tmp/scripts/release/"
notes() { ("$tmp/scripts/release/check-release-notes.sh" 1.0.0 "${1:-}" >/dev/null 2>&1); echo $?; }
expect_eq "missing locale fails" "$(notes)" "1"
printf 'DRAFT: x\n- a\n' >"$tmp/fastlane/release-notes/v1.0.0/en-US.txt"; printf -- '- b\n' >"$tmp/fastlane/release-notes/v1.0.0/es-ES.txt"
expect_eq "draft passes for an RC" "$(notes)" "0"
expect_eq "draft fails --final" "$(notes --final)" "1"
printf -- '- a\n' >"$tmp/fastlane/release-notes/v1.0.0/en-US.txt"
expect_eq "final notes pass --final" "$(notes --final)" "0"
head -c 501 /dev/zero | tr '\0' 'x' >"$tmp/fastlane/release-notes/v1.0.0/es-ES.txt"
expect_eq "over 500 chars fails" "$(notes)" "1"

echo "start-release.sh"
origin="$(mktemp -d)"; git -C "$origin" init -q --bare -b develop
r="$(new_repo)"
mkdir -p "$r/scripts/release" "$r/scripts/store" "$r/fastlane/metadata/android/en-US" "$r/fastlane/metadata/android/es-ES"
cp "$SCRIPTS/start-release.sh" "$r/scripts/release/"; cp "$REPO_ROOT/scripts/store/check-listing.sh" "$r/scripts/store/"
for l in en-US es-ES; do echo T >"$r/fastlane/metadata/android/$l/title.txt"; echo S >"$r/fastlane/metadata/android/$l/short_description.txt"; echo F >"$r/fastlane/metadata/android/$l/full_description.txt"; done
git -C "$r" add -A; commit "$r" "chore: init"
git -C "$r" tag -a v1.0.0 -m t
commit "$r" "feat(triage): snooze a card"; commit "$r" "docs: noise"; commit "$r" "fix(search): offline copy"
git -C "$r" remote add origin "$origin"; git -C "$r" push -q origin develop --tags
(cd "$r" && scripts/release/start-release.sh 1.0.0 >/dev/null 2>&1); expect_eq "refuses a version not newer than the last" "$?" "1"
(cd "$r" && scripts/release/start-release.sh 1.1.0 >/dev/null 2>&1); expect_eq "cuts release/1.1.0" "$?" "0"
git -C "$origin" rev-parse -q --verify refs/heads/release/1.1.0 >/dev/null && pass "branch pushed" || fail "branch pushed"
seeded="$(git -C "$r" show release/1.1.0:fastlane/release-notes/v1.1.0/en-US.txt)"
grep -q '^DRAFT:' <<<"$seeded" && pass "notes are marked draft" || fail "notes are marked draft"
grep -q -- '- snooze a card' <<<"$seeded" && grep -q -- '- offline copy' <<<"$seeded" && ! grep -q noise <<<"$seeded" \
  && pass "notes list feat/fix only" || fail "notes list feat/fix only: $seeded"
log="$(git -C "$r" show release/1.1.0:CHANGELOG.md)"
grep -q '^## 1.1.0 — ' <<<"$log" && pass "changelog gets a section for the version" || fail "changelog section: $log"
grep -q -- '^- \*\*triage\*\*: snooze a card$' <<<"$log" && grep -q -- '^- \*\*search\*\*: offline copy$' <<<"$log" \
  && ! grep -q noise <<<"$log" && pass "changelog lists feat/fix with their scope" || fail "changelog items: $log"
[ "$(grep -n '### Features' <<<"$log" | cut -d: -f1)" -lt "$(grep -n '### Fixes' <<<"$log" | cut -d: -f1)" ] \
  && pass "features come before fixes" || fail "features come before fixes"
git -C "$r" switch -q develop
(cd "$r" && scripts/release/start-release.sh 1.2.0 >/dev/null 2>&1); expect_eq "refuses a second release in flight" "$?" "1"
git -C "$r" push -q origin 'v1.0.0^{commit}:refs/heads/main'
(cd "$r" && scripts/release/start-release.sh 1.0.1 --hotfix >/dev/null 2>&1); expect_eq "a hotfix jumps the queue" "$?" "0"
[ "$(git -C "$origin" rev-parse 'hotfix/1.0.1^')" = "$(git -C "$origin" rev-parse 'v1.0.0^{commit}')" ] \
  && pass "hotfix is cut from main" || fail "hotfix is cut from main"

# A newer section goes above the older ones, and a breaking change says so.
r5="$(new_repo)"
mkdir -p "$r5/scripts/release"; cp "$SCRIPTS/start-release.sh" "$r5/scripts/release/"
printf '# Changelog\n\nheader\n\n## 1.0.0 — 2026-10-07\n\nFirst release.\n' >"$r5/CHANGELOG.md"
git -C "$r5" add -A; commit "$r5" "chore: init"; git -C "$r5" tag -a v1.0.0 -m t
commit "$r5" "feat(sync)!: accounts"; commit "$r5" "chore: noise"
o5="$(mktemp -d)"; git -C "$o5" init -q --bare -b develop
git -C "$r5" remote add origin "$o5"; git -C "$r5" push -q origin develop --tags
(cd "$r5" && scripts/release/start-release.sh 2.0.0 >/dev/null 2>&1); expect_eq "cuts release/2.0.0" "$?" "0"
log5="$(git -C "$r5" show release/2.0.0:CHANGELOG.md)"
[ "$(grep -n '^## 2.0.0' <<<"$log5" | cut -d: -f1)" -lt "$(grep -n '^## 1.0.0' <<<"$log5" | cut -d: -f1)" ] \
  && grep -q '^header$' <<<"$log5" && pass "new section above the old, header kept" || fail "section order: $log5"
grep -q -- '^- \*\*sync\*\*: accounts (breaking)$' <<<"$log5" && pass "breaking change is marked" || fail "breaking: $log5"
r6="$(new_repo)"; mkdir -p "$r6/scripts/release"; cp "$SCRIPTS/start-release.sh" "$r6/scripts/release/"
git -C "$r6" add -A; commit "$r6" "chore: init"; git -C "$r6" tag -a v1.0.0 -m t; commit "$r6" "ci: only"
o6="$(mktemp -d)"; git -C "$o6" init -q --bare -b develop
git -C "$r6" remote add origin "$o6"; git -C "$r6" push -q origin develop --tags
(cd "$r6" && scripts/release/start-release.sh 1.0.1 >/dev/null 2>&1)
grep -q 'Maintenance only' <<<"$(git -C "$r6" show release/1.0.1:CHANGELOG.md)" \
  && pass "a release with no feat/fix says so" || fail "maintenance-only section"

echo
[ "$failures" -eq 0 ] && echo "all passed" || { echo "$failures failed"; exit 1; }
