#!/bin/bash
# Tests for the sync verification scripts' local-stack handling (#940). No
# device, no network, no Supabase: adb and supabase are stubs that record every
# call. A refusal is proven by the stub log staying empty, not by the message.
#   scripts/sync/test/sync-scripts-test.sh
set -uo pipefail
SYNC="$(cd "$(dirname "$0")/.." && pwd)"
REPO_ROOT="$(cd "$SYNC/../.." && pwd)"
failures=0
pass() { printf '  \033[32mok\033[0m   %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; failures=$((failures + 1)); }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
CALLS="$WORK/calls.log"
mkdir -p "$WORK/bin"
for tool in adb supabase sqlite3 curl python3; do
  printf '#!/bin/bash\necho "%s $*" >> "%s"\nexit 1\n' "$tool" "$CALLS" > "$WORK/bin/$tool"
  chmod +x "$WORK/bin/$tool"
done

# Runs one script from the repo root with the stubs first on PATH.
# Sets RC, OUT (stdout+stderr) and clears the call log beforehand.
run() {
  local target="$1" script="$2"; shift 2
  : > "$CALLS"
  OUT="$(cd "$REPO_ROOT" && env -i HOME="$HOME" PATH="$WORK/bin:/usr/bin:/bin" \
      ADB="$WORK/bin/adb" ${target:+MUVISS_SUPABASE_TARGET=$target} \
      "$SYNC/$script" "$@" 2>&1)"
  RC=$?
}

echo "local-stack refusals"

run local verify-migration.sh --force
if [ "$RC" -ne 0 ] && grep -q "does not run against the local stack" <<<"$OUT"; then
  pass "verify-migration refuses local, even with --force"
else fail "verify-migration local --force: rc=$RC out=$OUT"; fi
if [ ! -s "$CALLS" ]; then pass "verify-migration local makes no adb or supabase call"
else fail "verify-migration local called: $(cat "$CALLS")"; fi

run local verify-migration.sh
if grep -q "does not run against the local stack" <<<"$OUT"; then
  pass "verify-migration refuses local before the --force check"
else fail "verify-migration local without --force: out=$OUT"; fi

run local sync-status.sh
if [ "$RC" -ne 0 ] && grep -q "no local-stack mode" <<<"$OUT"; then
  pass "sync-status refuses local with a message naming the verify scripts"
else fail "sync-status local: rc=$RC out=$OUT"; fi
if [ ! -s "$CALLS" ]; then pass "sync-status local makes no adb, supabase or curl call"
else fail "sync-status local called: $(cat "$CALLS")"; fi

echo "hosted targets are not refused"

run "" verify-migration.sh
if [ "$RC" -eq 1 ] && grep -q "wipes the app's data" <<<"$OUT" && [ ! -s "$CALLS" ]; then
  pass "verify-migration default target still asks for --force first"
else fail "verify-migration default: rc=$RC out=$OUT calls=$(cat "$CALLS")"; fi

for f in "$SYNC"/*.sh; do
  bash -n "$f" || { fail "syntax: $f"; continue; }
done
pass "every sync script parses (bash -n)"

echo
if [ "$failures" -eq 0 ]; then
  echo "all passed"
else
  echo "$failures failure(s)"
  exit 1
fi
