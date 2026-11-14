#!/usr/bin/env bash
# Tests for worktree-setup.sh against a throwaway repository with a linked worktree.
#   scripts/dev/test/worktree-setup-test.sh
set -uo pipefail
SCRIPT="$(cd "$(dirname "$0")/.." && pwd)/worktree-setup.sh"
SECRET="SECRET-TEST-VALUE-do-not-print"
failures=0

# check NAME COMMAND...: passes when COMMAND succeeds.
check() {
  local name="$1"; shift
  if "$@"; then
    printf '  \033[32mok\033[0m   %s\n' "$name"
  else
    printf '  \033[31mFAIL\033[0m %s\n' "$name"
    failures=$((failures + 1))
  fi
}
is_mode_600() { [ "$(stat -f %Lp "$1" 2>/dev/null || stat -c %a "$1")" = 600 ]; }
contains() { case "$1" in *"$2"*) return 0 ;; *) return 1 ;; esac; }
lacks() { ! contains "$1" "$2"; }
same_as() { [ "$1" = "$2" ]; }

root="$(mktemp -d)"
trap 'rm -rf "$root"' EXIT
main="$root/main"
wt="$root/wt"
mkdir "$main"
git -C "$main" -c init.defaultBranch=main init -q
git -C "$main" -c user.name=test -c user.email=test@example.invalid commit -q --allow-empty -m init
git -C "$main" worktree add -q "$wt" -b feature

# Run the script from $1, leaving its combined output in $out and its status in $rc.
run_in() { out="$(cd "$1" && "$SCRIPT" 2>&1)"; rc=$?; }

echo "worktree-setup.sh"

printf 'sdk.dir=/opt/android-sdk\nTMDB_API_KEY=%s\n' "$SECRET" > "$main/local.properties"

run_in "$wt"
check "copies into a worktree and exits 0" [ "$rc" = 0 ]
check "the copy is byte-identical to the main checkout's file" cmp -s "$main/local.properties" "$wt/local.properties"
check "the copy is owner-only (0600)" is_mode_600 "$wt/local.properties"
check "the output does not print the secret" lacks "$out" "$SECRET"

printf 'keep-me\n' > "$wt/local.properties"
run_in "$wt"
check "an existing local.properties makes it a no-op (exit 0)" [ "$rc" = 0 ]
check "an existing local.properties is never overwritten" same_as "$(cat "$wt/local.properties")" "keep-me"

rm "$wt/local.properties"
before="$(cksum < "$main/local.properties")"
run_in "$main"
check "run from the main checkout it is a no-op (exit 0)" [ "$rc" = 0 ]
check "the main checkout's file is untouched" same_as "$(cksum < "$main/local.properties")" "$before"

rm "$main/local.properties"
run_in "$wt"
check "no source anywhere exits 1" [ "$rc" = 1 ]
check "no source creates nothing" [ ! -e "$wt/local.properties" ]
check "the no-source message names ANDROID_HOME" contains "$out" "ANDROID_HOME"

if [ "$failures" -gt 0 ]; then
  printf '%s failure(s)\n' "$failures"
  exit 1
fi
echo "all passed"
