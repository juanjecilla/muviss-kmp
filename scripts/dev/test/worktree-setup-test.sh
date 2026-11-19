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
# GNU stat first: on GNU, `stat -f` succeeds and prints filesystem data, so the
# BSD form must not be tried first. On BSD/macOS `stat -c` is an error.
file_mode() { stat -c %a "$1" 2>/dev/null || stat -f %Lp "$1"; }
is_mode_600() { [ "$(file_mode "$1")" = 600 ]; }
is_link() { [ -L "$1" ]; }
contains() { case "$1" in *"$2"*) return 0 ;; *) return 1 ;; esac; }
lacks() { ! contains "$1" "$2"; }
same_as() { [ "$1" = "$2" ]; }
# no_temp DIR: the script left no temp file behind in DIR.
no_temp() { ! ls -A "$1" | grep -q '^\.local\.properties\.'; }

root="$(mktemp -d)"
trap 'rm -rf "$root"' EXIT
main="$root/main"
wt="$root/wt"
mkdir "$main"
git -C "$main" -c init.defaultBranch=main init -q
git -C "$main" -c user.name=test -c user.email=test@example.invalid commit -q --allow-empty -m init
git -C "$main" worktree add -q "$wt" -b feature

# A cp shim for the race and partial-copy cases. It runs the real cp, then either
# creates the destination (another process won the race) or leaves a partial copy
# and fails. Only the script's own copy is affected.
shim="$root/shim"
mkdir "$shim"
REAL_CP="$(command -v cp)"
export REAL_CP
cat > "$shim/cp" <<'EOF'
#!/bin/bash
# The racing file appears before the real copy runs, so a copy that targets the
# destination directly would truncate it.
if [ -n "${RACE_TARGET:-}" ]; then
  printf 'keep-me\n' > "$RACE_TARGET"
  chmod 644 "$RACE_TARGET"
fi
"$REAL_CP" "$@" || exit $?
if [ -n "${FAIL_COPY:-}" ]; then
  echo partial > "${@: -1}"
  exit 1
fi
EOF
chmod +x "$shim/cp"

# Run the script from $1, leaving its combined output in $out and its status in $rc.
run_in() { out="$(cd "$1" && "$SCRIPT" 2>&1)"; rc=$?; }

echo "worktree-setup.sh"

printf 'sdk.dir=/opt/android-sdk\nTMDB_API_KEY=%s\n' "$SECRET" > "$main/local.properties"

run_in "$wt"
check "copies into a worktree and exits 0" [ "$rc" = 0 ]
check "the copy is byte-identical to the main checkout's file" cmp -s "$main/local.properties" "$wt/local.properties"
check "the copy is a regular file, not a link" [ ! -L "$wt/local.properties" ]
check "the copy is owner-only (0600)" is_mode_600 "$wt/local.properties"
check "the output does not print the secret" lacks "$out" "$SECRET"
check "a successful copy leaves no temp file" no_temp "$wt"

rm "$wt/local.properties"
chmod 666 "$main/local.properties"
out="$(cd "$wt" && umask 000 && "$SCRIPT" 2>&1)"; rc=$?
check "a world-writable source under umask 000 still exits 0" [ "$rc" = 0 ]
check "a world-writable source under umask 000 yields a 0600 copy" is_mode_600 "$wt/local.properties"
chmod 644 "$main/local.properties"

printf 'keep-me\n' > "$wt/local.properties"
run_in "$wt"
check "an existing local.properties makes it a no-op (exit 0)" [ "$rc" = 0 ]
check "an existing local.properties is never overwritten" same_as "$(cat "$wt/local.properties")" "keep-me"

rm "$wt/local.properties"
ln -s "$root/nowhere.properties" "$wt/local.properties"
run_in "$wt"
check "a dangling link at the destination exits 0" [ "$rc" = 0 ]
check "the dangling link is left a link" is_link "$wt/local.properties"
check "nothing is created where the dangling link points" [ ! -e "$root/nowhere.properties" ]
rm "$wt/local.properties"

rm -f "$wt/local.properties"
out="$(cd "$wt" && PATH="$shim:$PATH" RACE_TARGET="$wt/local.properties" "$SCRIPT" 2>&1)"; rc=$?
check "a destination created after the check is not replaced (exit 0)" [ "$rc" = 0 ]
check "the destination created meanwhile keeps its contents" same_as "$(cat "$wt/local.properties")" "keep-me"
check "the destination created meanwhile keeps its mode (644)" same_as "$(file_mode "$wt/local.properties")" 644
check "a lost race leaves no temp file" no_temp "$wt"
rm -f "$wt/local.properties"

out="$(cd "$wt" && PATH="$shim:$PATH" FAIL_COPY=1 "$SCRIPT" 2>&1)"; rc=$?
check "a failed copy exits 1" [ "$rc" = 1 ]
check "a failed copy publishes nothing" [ ! -e "$wt/local.properties" ]
check "a failed copy leaves no partial temp file" no_temp "$wt"

rm "$wt/local.properties" 2>/dev/null
mv "$main/local.properties" "$root/real.properties"
ln -s "$root/real.properties" "$main/local.properties"
run_in "$wt"
check "a symlinked source is copied (exit 0)" [ "$rc" = 0 ]
check "a symlinked source yields a regular file with the same bytes" cmp -s "$root/real.properties" "$wt/local.properties"
check "the copy from a symlinked source is not a link" [ ! -L "$wt/local.properties" ]
rm "$wt/local.properties" "$main/local.properties"
mv "$root/real.properties" "$main/local.properties"

if [ "$(id -u)" = 0 ]; then
  echo "  skip  unreadable source (running as root, which reads mode 000)"
else
  rm -f "$wt/local.properties"
  chmod 000 "$main/local.properties"
  run_in "$wt"
  check "an unreadable source exits 1" [ "$rc" = 1 ]
  check "an unreadable source publishes nothing" [ ! -e "$wt/local.properties" ]
  check "an unreadable source leaves no temp file" no_temp "$wt"
  chmod 644 "$main/local.properties"
fi

rm -f "$wt/local.properties"
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
