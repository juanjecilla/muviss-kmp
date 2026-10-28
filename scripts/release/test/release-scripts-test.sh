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
git -C "$r" tag -a v1.0.1-rc1 -m rc; commit "$r" "fix(search): after the rc"
expect_eq "a version in flight (its rc tag) bases the next one" "$(cd "$r" && "$SCRIPTS/next-version.sh" develop)" "1.0.2"
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
# Staged releases (ADR 0030): 1.2.0 is in beta with no final tag when the train
# runs again. The next version follows it, from where its branch left develop.
r8="$(new_repo)"; commit "$r8" "chore: init"; git -C "$r8" tag -a v1.1.0 -m t
commit "$r8" "feat(lists): shipped in 1.2.0"; git -C "$r8" branch release/1.2.0
git -C "$r8" switch -q release/1.2.0; commit "$r8" "fix(lists): a release fix"; git -C "$r8" tag -a v1.2.0-rc2 -m rc
git -C "$r8" switch -q develop; commit "$r8" "fix(search): after the cut"
expect_eq "a version in beta bases the next cut" "$(cd "$r8" && "$SCRIPTS/next-version.sh" develop)" "1.2.1"
commit "$r8" "feat(triage): after the cut"
expect_eq "a feature after the cut bumps past it" "$(cd "$r8" && "$SCRIPTS/next-version.sh" develop)" "1.3.0"
git -C "$r8" tag -d v1.2.0-rc2 >/dev/null
expect_eq "a release branch with no rc yet counts" "$(cd "$r8" && "$SCRIPTS/next-version.sh" develop)" "1.3.0"
expect_eq "previous-release before a version skips newer ones" \
  "$(cd "$r8" && "$SCRIPTS/previous-release.sh" develop 1.2.0 | cut -d' ' -f1)" "1.1.0"
r9="$(new_repo)"; commit "$r9" "chore: init"; git -C "$r9" tag -a v1.1.0 -m t; git -C "$r9" branch release/1.2.0
(cd "$r9" && "$SCRIPTS/next-version.sh" develop >/dev/null 2>&1); expect_eq "nothing since the cut exits 3" "$?" "3"
# A release line with no common ancestor (a shallow clone) is an error, not "no release".
git -C "$r9" switch -q --orphan release/1.3.0; commit "$r9" "chore: unrelated"; git -C "$r9" switch -q develop
(cd "$r9" && "$SCRIPTS/next-version.sh" develop >/dev/null 2>&1); expect_eq "a failed lookup fails the version" "$?" "1"

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
cp "$SCRIPTS/start-release.sh" "$SCRIPTS/release-notes.sh" "$SCRIPTS/previous-release.sh" "$r/scripts/release/"; cp "$REPO_ROOT/scripts/store/check-listing.sh" "$r/scripts/store/"
for l in en-US es-ES; do echo T >"$r/fastlane/metadata/android/$l/title.txt"; echo S >"$r/fastlane/metadata/android/$l/short_description.txt"; echo F >"$r/fastlane/metadata/android/$l/full_description.txt"; done
git -C "$r" add -A; commit "$r" "chore: init"
git -C "$r" tag -a v1.0.0 -m t
commit "$r" "feat(triage): snooze a card"; commit "$r" "docs: noise"; commit "$r" "fix(search): offline copy"
git -C "$r" remote add origin "$origin"; git -C "$r" push -q origin develop --tags
(cd "$r" && scripts/release/start-release.sh 1.0.0 >/dev/null 2>&1); expect_eq "refuses a version not newer than the last" "$?" "1"
(cd "$r" && scripts/release/start-release.sh 1.1.0 >/dev/null 2>&1); expect_eq "cuts release/1.1.0" "$?" "0"
git -C "$origin" rev-parse -q --verify refs/heads/release/1.1.0 >/dev/null && pass "branch pushed" || fail "branch pushed"
seeded="$(git -C "$r" show release/1.1.0:fastlane/release-notes/v1.1.0/en-US.txt)"
! grep -q '^DRAFT:' <<<"$seeded" && pass "notes are final, not drafts" || fail "notes are final, not drafts: $seeded"
grep -q -- '- Snooze a card' <<<"$seeded" && grep -q -- '- Offline copy' <<<"$seeded" && ! grep -q noise <<<"$seeded" \
  && pass "notes list feat/fix only" || fail "notes list feat/fix only: $seeded"
log="$(git -C "$r" show release/1.1.0:CHANGELOG.md)"
grep -q '^## 1.1.0 — ' <<<"$log" && pass "changelog gets a section for the version" || fail "changelog section: $log"
grep -q -- '^- \*\*triage\*\*: snooze a card$' <<<"$log" && grep -q -- '^- \*\*search\*\*: offline copy$' <<<"$log" \
  && ! grep -q noise <<<"$log" && pass "changelog lists feat/fix with their scope" || fail "changelog items: $log"
[ "$(grep -n '### Features' <<<"$log" | cut -d: -f1)" -lt "$(grep -n '### Fixes' <<<"$log" | cut -d: -f1)" ] \
  && pass "features come before fixes" || fail "features come before fixes"
git -C "$r" switch -q develop
(cd "$r" && scripts/release/start-release.sh 1.2.0 >/dev/null 2>&1); expect_eq "refuses a second release while one is open" "$?" "1"
(cd "$r" && scripts/release/start-release.sh 1.2.0 --stagger-checked >/dev/null 2>&1); expect_eq "--stagger-checked lets the train's cut through" "$?" "0"
git -C "$r" switch -q develop
git -C "$r" push -q origin 'v1.0.0^{commit}:refs/heads/main'
(cd "$r" && scripts/release/start-release.sh 1.0.1 --hotfix >/dev/null 2>&1); expect_eq "a hotfix jumps the queue" "$?" "0"
[ "$(git -C "$origin" rev-parse 'hotfix/1.0.1^')" = "$(git -C "$origin" rev-parse 'v1.0.0^{commit}')" ] \
  && pass "hotfix is cut from main" || fail "hotfix is cut from main"

# A newer section goes above the older ones, and a breaking change says so.
r5="$(new_repo)"
mkdir -p "$r5/scripts/release"; cp "$SCRIPTS/start-release.sh" "$SCRIPTS/previous-release.sh" "$r5/scripts/release/"
printf '# Changelog\n\nheader\n\n## 1.0.0 — 2026-10-07\n\nFirst release.\n' >"$r5/CHANGELOG.md"
git -C "$r5" add -A; commit "$r5" "chore: init"; git -C "$r5" tag -a v1.0.0 -m t
commit "$r5" "feat(sync)!: accounts"; commit "$r5" "feat: great news!"; commit "$r5" "chore: noise"
o5="$(mktemp -d)"; git -C "$o5" init -q --bare -b develop
git -C "$r5" remote add origin "$o5"; git -C "$r5" push -q origin develop --tags
(cd "$r5" && scripts/release/start-release.sh 2.0.0 >/dev/null 2>&1); expect_eq "cuts release/2.0.0" "$?" "0"
log5="$(git -C "$r5" show release/2.0.0:CHANGELOG.md)"
[ "$(grep -n '^## 2.0.0' <<<"$log5" | cut -d: -f1)" -lt "$(grep -n '^## 1.0.0' <<<"$log5" | cut -d: -f1)" ] \
  && grep -q '^header$' <<<"$log5" && pass "new section above the old, header kept" || fail "section order: $log5"
grep -q -- '^- \*\*sync\*\*: accounts (breaking)$' <<<"$log5" && pass "breaking change is marked" || fail "breaking: $log5"
grep -q -- '^- great news!$' <<<"$log5" && pass "a ! in the subject is not breaking" || fail "subject bang: $log5"
r6="$(new_repo)"; mkdir -p "$r6/scripts/release"; cp "$SCRIPTS/start-release.sh" "$SCRIPTS/previous-release.sh" "$r6/scripts/release/"
git -C "$r6" add -A; commit "$r6" "chore: init"; git -C "$r6" tag -a v1.0.0 -m t; commit "$r6" "ci: only"
o6="$(mktemp -d)"; git -C "$o6" init -q --bare -b develop
git -C "$r6" remote add origin "$o6"; git -C "$r6" push -q origin develop --tags
(cd "$r6" && scripts/release/start-release.sh 1.0.1 >/dev/null 2>&1)
grep -q 'Maintenance only' <<<"$(git -C "$r6" show release/1.0.1:CHANGELOG.md)" \
  && pass "a release with no feat/fix says so" || fail "maintenance-only section"

echo "release-issue.sh (ADR 0030)"
# A fake gh: records every call, answers the reads the script makes.
fakebin="$(mktemp -d)"
cat >"$fakebin/gh" <<'FAKEGH'
#!/bin/bash
echo "gh $*" >>"$FAKE_GH_LOG"
case "$*" in
  "issue list"*"title,body"*) printf '%s' "${FAKE_ISSUES_JSON:-[]}" ;;
  "issue list"*) printf '%s' "${FAKE_TITLES:-}" ;;
  "api --paginate"*) printf '%s' "${FAKE_MILESTONE:-}" ;;
esac
exit 0
FAKEGH
chmod +x "$fakebin/gh"
export GH_REPO=muviss/test FAKE_GH_LOG="$(mktemp)"
r7="$(new_repo)"; mkdir -p "$r7/scripts/release" "$r7/fastlane"
cp "$SCRIPTS/release-issue.sh" "$r7/scripts/release/"; cp "$REPO_ROOT/fastlane/release_stages.rb" "$r7/fastlane/"
# The script finds its repo root from its own path, so every call runs the copy
# inside the throwaway repo; the worktree's copy would query the real origin.
RI="$r7/scripts/release/release-issue.sh"
issue() { PATH="$fakebin:$PATH" "$RI" "$@"; }
commit "$r7" "chore: init"
o7="$(mktemp -d)"; git -C "$o7" init -q --bare -b develop; git -C "$r7" remote add origin "$o7"
git -C "$r7" push -q origin develop
git -C "$r7" switch -q -c release/1.1.0; GIT_COMMITTER_DATE="2026-10-05T12:00:00Z" git -C "$r7" commit -q --allow-empty -m "chore(release): notes"
git -C "$r7" push -q origin release/1.1.0; git -C "$r7" switch -q develop
(cd "$r7" && issue ensure 1.1.0 2026-10-12 >/dev/null); expect_eq "a new version gets a milestone" "$(grep -c 'api -X POST repos/muviss/test/milestones' "$FAKE_GH_LOG")" "1"
expect_eq "and an issue titled Release X.Y.Z" "$(grep -c -- '--title Release 1.1.0 --label release --milestone 1.1.0' "$FAKE_GH_LOG")" "1"
: >"$FAKE_GH_LOG"; FAKE_TITLES="Release 1.1.0" FAKE_MILESTONE="7" issue ensure 1.1.0 2026-10-12 >/dev/null
expect_eq "an existing issue is left alone" "$(grep -c 'issue create' "$FAKE_GH_LOG")" "0"
: >"$FAKE_GH_LOG"; FAKE_TITLES="Release 1.1.01" issue ensure 1.1.0 2026-10-12 >/dev/null
expect_eq "a near title does not count" "$(grep -c 'issue create' "$FAKE_GH_LOG")" "1"
expect_eq "cut-date is the first commit past develop" "$(cd "$r7" && PATH="$fakebin:$PATH" "$RI" cut-date 1.1.0)" "2026-10-05"
: >"$FAKE_GH_LOG"; plan_out="$(cd "$r7" && FAKE_ISSUES_JSON='[]' PATH="$fakebin:$PATH" "$RI" plan)"
expect_eq "a branch with no issue is adopted" "$(ruby -rjson -e 'print JSON.parse(ARGV[0])["adopt"].join(",")' "$plan_out")" "1.1.0"
expect_eq "and it holds the train, being internal" "$(ruby -rjson -e 'print JSON.parse(ARGV[0])["hold"].nil? ? "none" : "held"' "$plan_out")" "held"
beta_json='[{"title":"Release 1.1.0","body":"<!-- muviss-release: {\"version\":\"1.1.0\",\"stage\":\"beta\"} -->"}]'
plan_out="$(cd "$r7" && FAKE_ISSUES_JSON="$beta_json" PATH="$fakebin:$PATH" "$RI" plan)"
expect_eq "a version in beta does not hold the train" "$(ruby -rjson -e 'print JSON.parse(ARGV[0])["hold"].nil? ? "none" : "held"' "$plan_out")" "none"
: >"$FAKE_GH_LOG"; (cd "$r7" && PATH="$fakebin:$PATH" "$RI" labels)
expect_eq "labels are created idempotently (release, release-blocker)" "$(grep -c 'label create' "$FAKE_GH_LOG")" "2"
PATH="$fakebin:$PATH" "$SCRIPTS/release-issue.sh" bogus >/dev/null 2>&1; expect_eq "an unknown command is refused" "$?" "2"

echo "version-code.sh (ADR 0030)"
expect_eq "1.1.0-rc4 is 1010004" "$("$SCRIPTS/version-code.sh" v1.1.0-rc4)" "1010004"
expect_eq "a final tag is rc 0" "$("$SCRIPTS/version-code.sh" v1.0.0)" "1000000"
expect_eq "1.0.1-rc12 is 1000112" "$("$SCRIPTS/version-code.sh" v1.0.1-rc12)" "1000112"
expect_eq "9.99.99-rc99 is 9999999" "$("$SCRIPTS/version-code.sh" v9.99.99-rc99)" "9999999"
expect_eq "leading zeros are decimal" "$("$SCRIPTS/version-code.sh" v1.08.09-rc08)" "1080908"
expect_eq "next RC outranks the count-based rc3 (~176)" "$(( $("$SCRIPTS/version-code.sh" v1.1.0-rc4) > 176 ))" "1"
expect_eq "rc3 of 1.1.0 is below rc4" "$(( $("$SCRIPTS/version-code.sh" v1.1.0-rc3) < $("$SCRIPTS/version-code.sh" v1.1.0-rc4) ))" "1"
"$SCRIPTS/version-code.sh" 1.1.0 >/dev/null 2>&1; expect_eq "a bare version without v is refused" "$?" "2"
"$SCRIPTS/version-code.sh" v1.1.0-4 >/dev/null 2>&1; expect_eq "a dash without rc is refused" "$?" "2"
"$SCRIPTS/version-code.sh" v1.18446744073709551616.0 >/dev/null 2>&1; expect_eq "a minor that would wrap to 0 is refused" "$?" "2"
"$SCRIPTS/version-code.sh" v18446744073709551617.0.0 >/dev/null 2>&1; expect_eq "a major that would wrap is refused" "$?" "2"
"$SCRIPTS/version-code.sh" v2148.0.0 >/dev/null 2>&1; expect_eq "a major past Int is refused" "$?" "2"
"$SCRIPTS/version-code.sh" v1.100.0 >/dev/null 2>&1; expect_eq "minor above 99 is refused" "$?" "2"
"$SCRIPTS/version-code.sh" v1.0.0-rc100 >/dev/null 2>&1; expect_eq "rc above 99 is refused" "$?" "2"
"$SCRIPTS/version-code.sh" >/dev/null 2>&1; expect_eq "no argument is refused" "$?" "2"


echo "forward-fix.sh (ADR 0030)"
ffbin="$(mktemp -d)"
cat >"$ffbin/gh" <<'FAKEGH'
#!/bin/bash
echo "gh $*" >>"$FAKE_GH_LOG"
case "$*" in
  "pr list"*) printf '%s' "${FAKE_OPEN_PR:-}" ;;
esac
exit 0
FAKEGH
chmod +x "$ffbin/gh"
export FAKE_GH_LOG="$(mktemp)" GH_REPO=muviss/test
f="$(new_repo)"; mkdir -p "$f/scripts/release"; cp "$SCRIPTS/forward-fix.sh" "$f/scripts/release/"
echo base >"$f/a.txt"; git -C "$f" add -A; commit "$f" "chore: init"
fo="$(mktemp -d)"; git -C "$fo" init -q --bare -b develop; git -C "$f" remote add origin "$fo"
git -C "$f" push -q origin develop
git -C "$f" switch -q -c release/1.2.0; git -C "$f" push -q origin release/1.2.0; git -C "$f" switch -q develop
git -C "$f" switch -q -c hotfix/1.0.1; git -C "$f" push -q origin hotfix/1.0.1; git -C "$f" switch -q develop
git -C "$f" switch -q -c release/1.1.0; echo fix >"$f/fix.txt"; git -C "$f" add -A; commit "$f" "fix: the old line"
FIXSHA="$(git -C "$f" rev-parse HEAD)"; git -C "$f" push -q origin release/1.1.0
git -C "$f" switch -q -c side release/1.1.0; echo side >"$f/side.txt"; git -C "$f" add -A; commit "$f" "fix: side"
git -C "$f" switch -q release/1.1.0; git -C "$f" merge -q --no-ff side -m "Merge side" ; MERGESHA="$(git -C "$f" rev-parse HEAD)"
git -C "$f" switch -q develop
git -C "$f" switch -q -c release/1.3.0 develop; echo other >"$f/fix.txt"; git -C "$f" add -A; commit "$f" "chore: a conflicting line"
git -C "$f" push -q origin release/1.3.0; git -C "$f" switch -q develop
git -C "$f" switch -q -c release/1.4.0 develop; echo fix >"$f/fix.txt"; git -C "$f" add -A; commit "$f" "fix: the old line, landed here too"
git -C "$f" push -q origin release/1.4.0; git -C "$f" switch -q develop
ff() { (cd "$f" && PATH="$ffbin:$PATH" "$f/scripts/release/forward-fix.sh" "$@"); }
expect_eq "a hotfix forwards to every newer release line, then develop" "$(ff targets hotfix/1.0.1 | tr '\n' ' ')" "release/1.1.0 release/1.2.0 release/1.3.0 release/1.4.0 develop "
expect_eq "a release forwards only to newer ones and develop" "$(ff targets release/1.2.0 | tr '\n' ' ')" "release/1.3.0 release/1.4.0 develop "
: >"$FAKE_GH_LOG"; expect_eq "a clean cherry-pick opens its PR" "$(ff forward 7 release/1.1.0 "$FIXSHA" release/1.2.0)" "forward #7 to release/1.2.0: opened (clean)"
expect_eq "the forward branch is on origin" "$(git -C "$fo" rev-parse --verify -q forward/pr-7-to-release-1.2.0 >/dev/null && echo yes)" "yes"
expect_eq "the PR goes to the target, labelled no-landing" "$(grep -c -- '--base release/1.2.0 --head forward/pr-7-to-release-1.2.0' "$FAKE_GH_LOG")" "1"
expect_eq "and carries no-landing" "$(grep -c -- '--label no-landing' "$FAKE_GH_LOG")" "1"
: >"$FAKE_GH_LOG"; expect_eq "a conflict still opens the PR, empty, for a person" "$(ff forward 7 release/1.1.0 "$FIXSHA" release/1.3.0)" "forward #7 to release/1.3.0: opened (conflict)"
expect_eq "its body names the conflict" "$(grep -c 'Conflicts' "$FAKE_GH_LOG")" "1"
expect_eq "the conflicting branch carries only the marker commit" "$(git -C "$fo" log --format=%s -n 1 forward/pr-7-to-release-1.3.0)" "chore(release): forward #7 to release/1.3.0 needs a person"
expect_eq "a merge commit is forwarded as its branch's change (-m 1)" "$(ff forward 8 release/1.1.0 "$MERGESHA" release/1.2.0 >/dev/null && git -C "$fo" show forward/pr-8-to-release-1.2.0:side.txt)" "side"
: >"$FAKE_GH_LOG"; expect_eq "an open forward PR is not opened twice" "$(FAKE_OPEN_PR=9 ff forward 7 release/1.1.0 "$FIXSHA" release/1.2.0)" "forward #7 to release/1.2.0: already open as #9"
expect_eq "and no PR is created for it" "$(grep -c 'pr create' "$FAKE_GH_LOG")" "0"
: >"$FAKE_GH_LOG"; expect_eq "a target that already has the fix is skipped" "$(ff forward 7 release/1.1.0 "$FIXSHA" release/1.4.0)" "forward #7 to release/1.4.0: already there, skipped"
expect_eq "and gets no PR or branch" "$(grep -c 'pr create' "$FAKE_GH_LOG")$(git -C "$fo" rev-parse --verify -q forward/pr-7-to-release-1.4.0 >/dev/null && echo pushed)" "0"
ff bogus >/dev/null 2>&1; expect_eq "an unknown command is refused" "$?" "2"

echo "milestone.sh (ADR 0030)"
mbin="$(mktemp -d)"
cat >"$mbin/gh" <<'FAKEGH'
#!/bin/bash
echo "gh $*" >>"$FAKE_GH_LOG"
case "$*" in
  "api --paginate"*)
    # A lost race: the milestone exists once the other run's POST landed.
    if [ -n "${FAKE_RACE:-}" ] && [ -e "$FAKE_RACE" ]; then printf '3'; else printf '%s' "${FAKE_MILESTONE:-}"; fi ;;
  "api -X POST"*)
    if [ -n "${FAKE_RACE:-}" ]; then touch "$FAKE_RACE"; echo '{"message":"Validation Failed","code":"already_exists"}' >&2; exit 1; fi ;;
esac
exit 0
FAKEGH
chmod +x "$mbin/gh"
export FAKE_GH_LOG="$(mktemp)" GH_REPO=muviss/test
ms() { PATH="$mbin:$PATH" "$SCRIPTS/milestone.sh" "$@"; }
: >"$FAKE_GH_LOG"; ms 12 release/1.1.0 >/dev/null
expect_eq "a new milestone is created" "$(grep -c 'api -X POST repos/muviss/test/milestones' "$FAKE_GH_LOG")" "1"
expect_eq "the PR is put under it" "$(grep -c -- 'pr edit 12 --repo muviss/test --milestone 1.1.0' "$FAKE_GH_LOG")" "1"
: >"$FAKE_GH_LOG"; FAKE_MILESTONE="3" ms 12 hotfix/1.0.1 >/dev/null
expect_eq "an existing milestone is reused" "$(grep -c 'api -X POST' "$FAKE_GH_LOG")" "0"
expect_eq "a hotfix gets its own milestone" "$(grep -c -- '--milestone 1.0.1' "$FAKE_GH_LOG")" "1"
: >"$FAKE_GH_LOG"; ms 12 develop >/dev/null
expect_eq "a non-release base is left alone" "$(wc -l <"$FAKE_GH_LOG" | tr -d ' ')" "0"
race="$(mktemp -u)"; : >"$FAKE_GH_LOG"
out="$(FAKE_RACE="$race" ms 13 release/1.4.0 2>&1)"; expect_eq "a lost creation race still succeeds" "$?" "0"
expect_eq "and the PR still gets the milestone" "$(grep -c -- 'pr edit 13 --repo muviss/test --milestone 1.4.0' "$FAKE_GH_LOG")" "1"


echo "apple-setup.sh --verify (docs/RELEASING.md section 9)"
abin="$(mktemp -d)"
trap 'rm -rf -- "$abin" "${av:-}" "${FAKE_GH_LOG:-}"' EXIT
cat >"$abin/gh" <<'FAKEGH'
#!/bin/bash
echo "gh $*" >>"$FAKE_GH_LOG"
case "$*" in
  "auth status"*) exit "${FAKE_AUTH:-0}" ;;
  "secret list"*) printf '%b' "${FAKE_SECRETS:-}" | awk 'NF{print $1"\tupdated"}' ;;
  "variable list"*) printf '%b' "${FAKE_VARS:-}" | awk 'NF{print $1"\tupdated"}' ;;
esac
exit 0
FAKEGH
chmod +x "$abin/gh"
export FAKE_GH_LOG="$(mktemp)"
av="$(mktemp -d)"; mkdir -p "$av/scripts/release"; cp "$SCRIPTS/apple-setup.sh" "$av/scripts/release/"
ALL_SECRETS='APP_STORE_CONNECT_KEY_ID\nAPP_STORE_CONNECT_ISSUER_ID\nAPP_STORE_CONNECT_KEY_P8\nSENTRY_AUTH_TOKEN\nSENTRY_ORG\nSENTRY_PROJECT\n'
ALL_VARS='APPLE_TEAM_ID\nTESTFLIGHT_EXTERNAL_GROUPS\n'
verify() { (cd "$av" && PATH="$abin:$PATH" env -u APPLE_ASC_P8_PATH FAKE_SECRETS="$1" FAKE_VARS="$2" "$av/scripts/release/apple-setup.sh" --verify </dev/null >/dev/null 2>&1); echo $?; }
expect_eq "verify passes when every name CI reads is set" "$(verify "$ALL_SECRETS" "$ALL_VARS")" "0"
expect_eq "verify fails when the Sentry org is missing" "$(verify "$(printf '%b' "$ALL_SECRETS" | grep -v SENTRY_ORG)" "$ALL_VARS")" "1"
expect_eq "verify fails when the external groups variable is missing" "$(verify "$ALL_SECRETS" 'APPLE_TEAM_ID\n')" "1"
expect_eq "the optional internal groups variable may be unset" "$(verify "$ALL_SECRETS" "$ALL_VARS")" "0"
expect_eq "verify refuses when gh is not signed in" "$(FAKE_AUTH=1 verify "$ALL_SECRETS" "$ALL_VARS")" "1"
: >"$FAKE_GH_LOG"; verify "" "" >/dev/null
expect_eq "verify reads names only: no set, no value" "$(grep -c -E 'secret set|variable set' "$FAKE_GH_LOG")" "0"
expect_eq "the wizard asks for the Admin role and keeps Sign in with Apple out" "$(grep -c 'Access: Admin' "$SCRIPTS/apple-setup.sh")$(grep -c 'Do NOT tick Sign in with Apple' "$SCRIPTS/apple-setup.sh")" "11"
if command -v shellcheck >/dev/null 2>&1; then
  shellcheck "$SCRIPTS/apple-setup.sh" >/dev/null 2>&1 && pass "apple-setup.sh passes shellcheck" || fail "apple-setup.sh passes shellcheck"
fi

echo "release-notes.sh"
r="$(new_repo)"
mkdir -p "$r/scripts/release" "$r/fastlane/metadata/android/en-US" "$r/fastlane/metadata/android/es-ES"
cp "$SCRIPTS/release-notes.sh" "$SCRIPTS/previous-release.sh" "$r/scripts/release/"
git -C "$r" add -A; commit "$r" "chore: init"; git -C "$r" tag -a v1.0.0 -m t
commit "$r" "feat(settings): mute genres (#389)"
commit "$r" "feat(onboarding): first-run introduction (EPIC 47) (#323)"
commit "$r" "fix(search): offline copy #412"
commit "$r" "feat(lists): #413 reorder a list"
commit "$r" "fix(profile): a signed-out user is offered sign-in, not a paywall"
commit "$r" "feat(cowatch): share the invite"
commit "$r" "fix(release): the train skips a holiday"
commit "$r" "fix(ios): widget undo"
commit "$r" "feat(settings): bring your own TMDB key"
git -C "$r" tag -a v1.1.0-rc1 -m rc
rn() { (cd "$r" && scripts/release/release-notes.sh 1.1.0 "$@" 2>/dev/null); }
out="$(rn)"
grep -q -- '- Mute genres$' <<<"$out" && grep -q -- '- First-run introduction$' <<<"$out" && grep -q -- '- Reorder a list$' <<<"$out" && grep -q -- "- Offline copy$" <<<"$out" \
  && pass "lists user-facing feat/fix, capitalised, without issue refs" || fail "user-facing items: $out"
grep -qiE 'paywall|invite|holiday|widget|tmdb' <<<"$out" && fail "hides paid, dark, infra and other-platform work: $out" \
  || pass "hides paid, dark, infra and other-platform work"
[ "$(grep -n 'Mute genres' <<<"$out" | cut -d: -f1)" -lt "$(grep -n 'Offline copy' <<<"$out" | cut -d: -f1)" ] \
  && pass "features come before fixes" || fail "features before fixes: $out"
grep -q '^Muviss 1.1.0 trae 3 novedades y 1 corrección de errores.$' <<<"$out" \
  && pass "Spanish counts what changed" || fail "Spanish line: $out"
notes_dir="$r/fastlane/release-notes/v1.1.0"
rn --write >/dev/null
! grep -q DRAFT "$notes_dir/en-US.txt" && [ -f "$notes_dir/.generated" ] && pass "--write writes every locale and its ledger" || fail "--write"
commit "$r" "fix(lists): keep the sort order"
rn --write >/dev/null
grep -q -- '- Keep the sort order' "$notes_dir/en-US.txt" && grep -q '2 correcciones' "$notes_dir/es-ES.txt" \
  && pass "a later fix is picked up by the next run" || fail "regenerates: $(cat "$notes_dir"/*.txt)"
echo "Hand-written notes." >"$notes_dir/en-US.txt"
commit "$r" "fix(lists): another one"
rn --write >/dev/null
[ "$(cat "$notes_dir/en-US.txt")" = "Hand-written notes." ] && pass "notes a person edited are kept" || fail "kept: $(cat "$notes_dir/en-US.txt")"
grep -q '3 correcciones' "$notes_dir/es-ES.txt" && pass "the untouched locale still follows the branch" || fail "es: $(cat "$notes_dir/es-ES.txt")"
printf 'DRAFT: rewrite me\n- x\n' >"$notes_dir/en-US.txt"
rn --write >/dev/null
grep -q "^What's new in Muviss 1.1.0:" "$notes_dir/en-US.txt" && pass "an old DRAFT file is replaced" || fail "draft replaced"
for i in $(seq 1 40); do commit "$r" "feat(lists): a fairly long feature description number $i that fills the notes"; done
rn --write >/dev/null
[ "$(wc -m <"$notes_dir/en-US.txt" | tr -d ' ')" -le 500 ] && tail -1 "$notes_dir/en-US.txt" | grep -q '^- Bug fixes and improvements.$' \
  && pass "stays within Play's 500 characters" || fail "500 cap: $(wc -m <"$notes_dir/en-US.txt")"
(cd "$r" && "$REPO_ROOT/scripts/release/check-release-notes.sh" 1.1.0 --final >/dev/null 2>&1 || true)
cp "$SCRIPTS/check-release-notes.sh" "$r/scripts/release/"
(cd "$r" && scripts/release/check-release-notes.sh 1.1.0 --final >/dev/null 2>&1); expect_eq "generated notes pass the main guard" "$?" "0"

# A `Notes: none` trailer keeps a commit out of the notes and the Spanish count.
# The trailer is read only from a body's last paragraph, in any case.
t="$(new_repo)"
mkdir -p "$t/scripts/release" "$t/fastlane/metadata/android/en-US" "$t/fastlane/metadata/android/es-ES"
cp "$SCRIPTS/release-notes.sh" "$SCRIPTS/previous-release.sh" "$t/scripts/release/"
git -C "$t" add -A; commit "$t" "chore: init"; git -C "$t" tag -a v1.0.0 -m t
commit "$t" "feat(lists): a visible feature"
git -C "$t" commit -q --allow-empty -F - <<'MSG'
feat(settings): a store-invisible feature

Notes: none
MSG
git -C "$t" commit -q --allow-empty -F - <<'MSG'
feat(lists): a mid-body line is prose

Notes: none

This paragraph follows the line above, so the line is not a trailer.
MSG
git -C "$t" commit -q --allow-empty -F - <<'MSG'
feat(triage): trailer above another trailer, any case

Reviewed-by: someone
notes: NONE
Co-Authored-By: Someone <someone@example.com>
MSG
git -C "$t" commit -q --allow-empty -F - <<'MSG'
fix(search): an invisible fix is opted out too

Notes: none
MSG
git -C "$t" commit -q --allow-empty -F - <<'MSG'
feat(search): a trailer that is not one: Notes: none, really

Notes: nothing
MSG
tn() { (cd "$t" && scripts/release/release-notes.sh 1.1.0 2>/dev/null); }
out="$(tn)"
# Listed: the plain feat, the prose mid-body feat, and the feat whose body has
# only a non-"none" Notes line. Opted out: the Notes: none feat, the feat with
# Notes: none among other trailers, and the fix.
grep -q -- '^- A visible feature$' <<<"$out" && grep -q -- '^- A mid-body line is prose$' <<<"$out" \
  && grep -q -- '^- A trailer that is not one: Notes: none, really$' <<<"$out" \
  && pass "features without an opt-out trailer are listed" || fail "listed features: $out"
grep -q -- 'store-invisible' <<<"$out" && fail "a Notes: none feat is listed: $out" \
  || pass "a Notes: none feat is absent"
grep -q -- 'another trailer' <<<"$out" && fail "Notes: none among other trailers is listed: $out" \
  || pass "Notes: none among other trailers, in any case, is absent"
grep -q -- 'invisible fix' <<<"$out" && fail "a Notes: none fix is listed: $out" \
  || pass "a Notes: none fix is absent"
grep -q '^Muviss 1.1.0 trae 3 novedades y mejoras.$' <<<"$out" \
  && pass "Spanish count matches the English list (opted-out commits not counted)" \
  || fail "Spanish count: $out"

echo
[ "$failures" -eq 0 ] && echo "all passed" || { echo "$failures failed"; exit 1; }
