#!/bin/bash
# Tests for scripts/ios/set-version.sh against throwaway git repos (macOS only:
# it needs PlistBuddy). The build number must equal the Android versionCode
# for the same git state; those vectors are the ones MuvissVersionTest pins.
#   scripts/ios/test/set-version-test.sh
set -uo pipefail
SCRIPTS="$(cd "$(dirname "$0")/.." && pwd)"
REPO_ROOT="$(cd "$SCRIPTS/../.." && pwd)"
failures=0
pass() { printf '  \033[32mok\033[0m   %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; failures=$((failures + 1)); }
expect_eq() { [ "$2" = "$3" ] && pass "$1" || fail "$1: expected '$3', got '$2'"; }

if [ ! -x /usr/libexec/PlistBuddy ]; then
  echo "skip: PlistBuddy is macOS only"
  exit 0
fi

# A throwaway repo carrying the two scripts the phase calls, so the test runs
# the real thing against a real git history.
new_repo() {
  local dir
  dir="$(mktemp -d)"
  mkdir -p "$dir/scripts/ios" "$dir/scripts/release"
  cp "$SCRIPTS/set-version.sh" "$dir/scripts/ios/"
  cp "$REPO_ROOT/scripts/release/version-code.sh" "$dir/scripts/release/"
  git -C "$dir" init -q -b develop
  git -C "$dir" config user.email t@example.com
  git -C "$dir" config user.name test
  git -C "$dir" config commit.gpgsign false
  git -C "$dir" config tag.gpgsign false
  git -C "$dir" add -A
  git -C "$dir" commit -q -m init
  echo "$dir"
}
commit() { git -C "$1" commit -q --allow-empty -m "$2"; }

# Runs the phase against a plist that starts out as the Xcode default, and
# prints "<short> <build>".
stamp() {
  local dir="$1" plist
  plist="$(mktemp -d)/Info.plist"
  cat >"$plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleShortVersionString</key><string>1.0</string>
<key>CFBundleVersion</key><string>1</string>
</dict></plist>
PLIST
  MUVISS_REPO_ROOT="$dir" "$dir/scripts/ios/set-version.sh" "$plist" >/dev/null || { echo "exit-$?"; return; }
  echo "$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$plist") $(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' "$plist")"
}

echo "set-version.sh"

r="$(new_repo)"
expect_eq "untagged, one commit: 0.1.0 with the commit count" "$(stamp "$r")" "0.1.0 1"
commit "$r" "feat: a"; commit "$r" "feat: b"
expect_eq "untagged: build is the commit count, never a dev label" "$(stamp "$r")" "0.1.0 3"

git -C "$r" tag -a v1.1.0-rc4 -m rc4
expect_eq "exact rc tag: numeric name, versionCode 1010004" "$(stamp "$r")" "1.1.0 1010004"

git -C "$r" tag -a v1.1.0 -m final
expect_eq "exact final tag on the same commit: rc 0 is 1010000" "$(stamp "$r")" "1.1.0 1010000"

commit "$r" "fix: ahead of the tag"
expect_eq "ahead of a tag: name from the tag, build falls back to count" "$(stamp "$r")" "1.1.0 4"

git -C "$r" tag -a v1.2.0-rc1 -m rc1
echo "# dirty" >>"$r/scripts/ios/set-version.sh"
expect_eq "dirty tree on a tag: not a release tag, build is the count" "$(stamp "$r")" "1.2.0 4"
git -C "$r" checkout -q -- scripts/ios/set-version.sh

r2="$(mktemp -d)"
mkdir -p "$r2/scripts/ios" && cp "$SCRIPTS/set-version.sh" "$r2/scripts/ios/"
expect_eq "no git: keeps the xcconfig values and succeeds" "$(MUVISS_REPO_ROOT="$r2" "$r2/scripts/ios/set-version.sh" "$r2/missing.plist" >/dev/null 2>&1; echo $?)" "0"

echo
if [ "$failures" -gt 0 ]; then echo "$failures failure(s)"; exit 1; fi
echo "all passed"
