#!/usr/bin/env bash
# Tests for worktree-setup.sh against a throwaway repository with a linked worktree.
#   scripts/dev/test/worktree-setup-test.sh
set -uo pipefail
ROOT_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
SCRIPT="$ROOT_DIR/scripts/dev/worktree-setup.sh"
SECRET="SECRET-TEST-VALUE-do-not-print"
ROTATED="ROTATED-TEST-VALUE-do-not-print"
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
same_file() { [ "$1" -ef "$2" ]; }
is_absolute() { case "$1" in /*) return 0 ;; *) return 1 ;; esac; }
# no_temp DIR: the script left no temp file behind in DIR.
no_temp() { ! ls -A "$1" | grep -q '^\.local\.properties\.'; }
# empty_dir DIR: DIR is a directory with no entries.
empty_dir() { [ -d "$1" ] && [ -z "$(ls -A "$1")" ]; }

root="$(mktemp -d)"
trap 'rm -rf "$root"' EXIT
main="$root/main"
wt="$root/wt"
mkdir "$main"
git -C "$main" -c init.defaultBranch=main init -q
# The repo's own .gitignore, so the gitignore checks below test the real rule.
cp "$ROOT_DIR/.gitignore" "$main/.gitignore"
git -C "$main" add .gitignore
git -C "$main" -c user.name=test -c user.email=test@example.invalid commit -q -m init
git -C "$main" worktree add -q "$wt" -b feature

# Shims for the race and failure cases; everything else runs the real tools.
#   RACE_LN=PATH      another process creates PATH just before the script's ln, so the
#                     publish (hard link for the copy, symlink for --link) meets an
#                     existing file.
#   RACE_LN_DIR=PATH  another process creates a directory at PATH just before the
#                     script's ln, which would otherwise link inside it.
#   FAIL_COPY=1       the copy leaves a partial file at its destination, then fails.
shim="$root/shim"
mkdir "$shim"
REAL_CP="$(command -v cp)"
REAL_LN="$(command -v ln)"
export REAL_CP REAL_LN
cat > "$shim/cp" <<'EOF'
#!/bin/bash
"$REAL_CP" "$@" || exit $?
if [ -n "${FAIL_COPY:-}" ]; then
  echo partial > "${@: -1}"
  exit 1
fi
EOF
cat > "$shim/ln" <<'EOF'
#!/bin/bash
if [ -n "${RACE_LN_DIR:-}" ]; then
  mkdir "$RACE_LN_DIR"
elif [ -n "${RACE_LN:-}" ]; then
  printf 'keep-me\n' > "$RACE_LN"
  chmod 644 "$RACE_LN"
fi
exec "$REAL_LN" "$@"
EOF
chmod +x "$shim/cp" "$shim/ln"

# Run the script from $1 with the remaining arguments, leaving its combined output
# in $out and its status in $rc.
run_in() { local dir="$1"; shift; out="$(cd "$dir" && "$SCRIPT" "$@" 2>&1)"; rc=$?; }

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
check "a dangling link to a foreign file is refused, as any foreign link is (exit 1)" [ "$rc" = 1 ]
check "the dangling link is left a link" is_link "$wt/local.properties"
check "nothing is created where the dangling link points" [ ! -e "$root/nowhere.properties" ]
rm -f "$wt/local.properties"

out="$(cd "$wt" && PATH="$shim:$PATH" RACE_LN="$wt/local.properties" "$SCRIPT" 2>&1)"; rc=$?
check "a destination created after the check is not replaced (exit 0)" [ "$rc" = 0 ]
check "the destination created meanwhile keeps its contents" same_as "$(cat "$wt/local.properties")" "keep-me"
check "the destination created meanwhile keeps its mode (644)" same_as "$(file_mode "$wt/local.properties")" 644
check "a lost race leaves no temp file" no_temp "$wt"
rm -f "$wt/local.properties"

out="$(cd "$wt" && PATH="$shim:$PATH" RACE_LN_DIR="$wt/local.properties" "$SCRIPT" 2>&1)"; rc=$?
check "a directory created at the destination during the run fails (exit 1)" [ "$rc" = 1 ]
check "the directory created meanwhile stays a directory" [ -d "$wt/local.properties" ]
check "no entry is added inside the directory created meanwhile" empty_dir "$wt/local.properties"
check "a lost directory race leaves no temp file" no_temp "$wt"
rmdir "$wt/local.properties"

out="$(cd "$wt" && PATH="$shim:$PATH" FAIL_COPY=1 "$SCRIPT" 2>&1)"; rc=$?
check "a failed copy exits 1" [ "$rc" = 1 ]
check "a failed copy publishes nothing" [ ! -e "$wt/local.properties" ]
check "a failed copy leaves no partial temp file" no_temp "$wt"

mkdir "$wt/local.properties"
run_in "$wt"
check "a directory at the destination is left alone (exit 0)" [ "$rc" = 0 ]
check "the directory at the destination stays a directory" [ -d "$wt/local.properties" ]
rmdir "$wt/local.properties"

rm -f "$wt/local.properties"
mv "$main/local.properties" "$root/real.properties"
ln -s "$root/real.properties" "$main/local.properties"
run_in "$wt"
check "a symlinked source is copied (exit 0)" [ "$rc" = 0 ]
check "a symlinked source yields a regular file with the same bytes" cmp -s "$root/real.properties" "$wt/local.properties"
check "the copy from a symlinked source is not a link" [ ! -L "$wt/local.properties" ]
rm -f "$wt/local.properties" "$main/local.properties"
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

echo "worktree-setup.sh --link"

printf 'sdk.dir=/opt/android-sdk\nTMDB_API_KEY=%s\n' "$SECRET" > "$main/local.properties"
run_in "$wt" --link
check "--link creates a symlink and exits 0" [ "$rc" = 0 ]
check "the link is a symlink" is_link "$wt/local.properties"
check "the link resolves to the main checkout's file" same_file "$wt/local.properties" "$main/local.properties"
check "the link target is absolute" is_absolute "$(readlink "$wt/local.properties")"
check "the output does not print the secret" lacks "$out" "$SECRET"
check "git ignores the link (no status entry)" [ -z "$(git -C "$wt" status --porcelain)" ]
check "git check-ignore matches local.properties as a link" git -C "$wt" check-ignore -q local.properties

link_before="$(readlink "$wt/local.properties")"
run_in "$wt" --link
check "--link on an existing matching link is a no-op (exit 0)" [ "$rc" = 0 ]
check "re-running leaves the same link in place" same_as "$(readlink "$wt/local.properties")" "$link_before"

printf 'sdk.dir=/opt/android-sdk\nTMDB_API_KEY=%s\n' "$ROTATED" > "$main/local.properties"
check "a link follows a key rotated in the main checkout" grep -q "$ROTATED" "$wt/local.properties"

run_in "$wt"
check "the default run on a matching link is a no-op (exit 0)" [ "$rc" = 0 ]
check "the default run leaves the link alone" is_link "$wt/local.properties"

run_in "$wt" --check
check "--check reports a matching link as set up (exit 0)" [ "$rc" = 0 ]
check "--check does not print the rotated key" lacks "$out" "$ROTATED"

rm "$wt/local.properties"
printf 'foreign\n' > "$root/elsewhere.properties"
ln -s "$root/elsewhere.properties" "$wt/local.properties"
run_in "$wt" --link
check "--link refuses a symlink to another file (exit 1)" [ "$rc" = 1 ]
check "the foreign link is left pointing where it did" same_as "$(readlink "$wt/local.properties")" "$root/elsewhere.properties"
check "the file the foreign link points at is untouched" same_as "$(cat "$root/elsewhere.properties")" "foreign"
run_in "$wt"
check "the default run also refuses a foreign symlink (exit 1)" [ "$rc" = 1 ]
run_in "$wt" --check
check "--check reports a foreign symlink (exit 1)" [ "$rc" = 1 ]

rm "$wt/local.properties"
ln -s "$main/local.properties" "$wt/local.properties"
rm "$main/local.properties"
run_in "$wt" --check
check "--check reports a dangling link to a missing main file (exit 1)" [ "$rc" = 1 ]
printf 'sdk.dir=/opt/android-sdk\nTMDB_API_KEY=%s\n' "$SECRET" > "$main/local.properties"
rm "$wt/local.properties"

printf 'keep-me\n' > "$wt/local.properties"
run_in "$wt" --link
check "--link refuses to replace a regular file (exit 1)" [ "$rc" = 1 ]
check "--link leaves the regular file as it was" same_as "$(cat "$wt/local.properties")" "keep-me"
check "--link leaves a regular file a regular file" [ ! -L "$wt/local.properties" ]
rm "$wt/local.properties"

out="$(cd "$wt" && PATH="$shim:$PATH" RACE_LN="$wt/local.properties" "$SCRIPT" --link 2>&1)"; rc=$?
check "--link does not replace a file created after the check (exit 1)" [ "$rc" = 1 ]
check "the file created meanwhile keeps its contents" same_as "$(cat "$wt/local.properties")" "keep-me"
check "the file created meanwhile stays a regular file" [ ! -L "$wt/local.properties" ]
check "a lost --link race leaves no temp file" no_temp "$wt"
rm -f "$wt/local.properties"

out="$(cd "$wt" && PATH="$shim:$PATH" RACE_LN_DIR="$wt/local.properties" "$SCRIPT" --link 2>&1)"; rc=$?
check "--link into a directory created meanwhile fails (exit 1)" [ "$rc" = 1 ]
check "the directory created meanwhile stays a directory" [ -d "$wt/local.properties" ]
check "--link adds no link inside the directory created meanwhile" empty_dir "$wt/local.properties"
rmdir "$wt/local.properties"

run_in "$wt" --bogus
check "an unknown option exits 2" [ "$rc" = 2 ]
run_in "$wt" --link --check
check "two options exit 2" [ "$rc" = 2 ]

echo "worktree-setup.sh --check and the staleness warning"

rm -f "$wt/local.properties"
run_in "$wt" --check
check "--check on an unset-up worktree exits 1" [ "$rc" = 1 ]
check "--check creates nothing" [ ! -e "$wt/local.properties" ]

run_in "$wt"
check "a default copy exits 0" [ "$rc" = 0 ]
run_in "$wt" --check
check "--check on an in-sync copy exits 0" [ "$rc" = 0 ]
run_in "$wt"
check "the default run on an in-sync copy does not warn" lacks "$out" "stale"

printf 'sdk.dir=/opt/android-sdk\nTMDB_API_KEY=%s\n' "$ROTATED" > "$main/local.properties"
copy_before="$(cksum < "$wt/local.properties")"
run_in "$wt"
check "the default run on a stale copy exits 0" [ "$rc" = 0 ]
check "the default run warns that the copy is stale" contains "$out" "stale"
check "the warning does not print either key" lacks "$out" "$ROTATED"
check "the warning does not print the old key" lacks "$out" "$SECRET"
check "the stale copy is left unchanged" same_as "$(cksum < "$wt/local.properties")" "$copy_before"
run_in "$wt" --check
check "--check on a stale copy exits 1" [ "$rc" = 1 ]
check "--check names the copy as stale" contains "$out" "stale"
check "--check does not print either key" lacks "$out" "$ROTATED"

run_in "$wt" --link
check "--link refuses to replace the stale copy (exit 1)" [ "$rc" = 1 ]
rm "$wt/local.properties"
run_in "$wt" --link
check "after removing the stale copy, --link links it (exit 0)" [ "$rc" = 0 ]
check "the refreshed worktree is a live link" is_link "$wt/local.properties"

echo "worktree-setup.sh --check when the main checkout's file cannot be compared"

rm -f "$wt/local.properties"
run_in "$wt"
check "a copy is made for the comparison cases (exit 0)" [ "$rc" = 0 ]
copy_before="$(cksum < "$wt/local.properties")"
mv "$main/local.properties" "$root/gone.properties"
run_in "$wt" --check
check "--check with a regular copy and a missing main file exits 1" [ "$rc" = 1 ]
check "--check with a missing main file does not print the key" lacks "$out" "$ROTATED"
run_in "$wt"
check "the default run with a missing main file still exits 0" [ "$rc" = 0 ]
check "the default run leaves the copy unchanged" same_as "$(cksum < "$wt/local.properties")" "$copy_before"
mv "$root/gone.properties" "$main/local.properties"

if [ "$(id -u)" = 0 ]; then
  echo "  skip  --check with an unreadable main file (running as root, which reads mode 000)"
else
  chmod 000 "$main/local.properties"
  run_in "$wt" --check
  check "--check with an unreadable main file exits 1" [ "$rc" = 1 ]
  run_in "$wt"
  check "the default run with an unreadable main file exits 0" [ "$rc" = 0 ]
  chmod 644 "$main/local.properties"
fi

echo "worktree-setup.sh in the main checkout"

rm -f "$wt/local.properties"
run_in "$main" --check
check "--check from the main checkout exits 0" [ "$rc" = 0 ]
check "--check from the main checkout changes nothing" same_as "$(cat "$main/local.properties")" "sdk.dir=/opt/android-sdk
TMDB_API_KEY=$ROTATED"

if [ "$failures" -gt 0 ]; then
  printf '%s failure(s)\n' "$failures"
  exit 1
fi
echo "all passed"
