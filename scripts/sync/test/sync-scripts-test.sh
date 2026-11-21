#!/bin/bash
# Tests for the sync verification scripts (#940, #936). No device, no network,
# no Supabase: adb, supabase and curl are stubs that record every call and
# answer with the statuses a test asks for. sqlite3 and python3 are the real
# ones, because the local session writes a real database and reads real JSON.
#
# A refusal is proven by the call log staying empty, not by its message. A
# failure is proven by its exit status and by the success line being absent.
#
#   scripts/sync/test/sync-scripts-test.sh
#
# Needs bash, sqlite3 and python3 on the path (pr-guards.yml runs it on Ubuntu).
set -uo pipefail
SYNC="$(cd "$(dirname "$0")/.." && pwd)"
REPO_ROOT="$(cd "$SYNC/../.." && pwd)"
failures=0
pass() { printf '  \033[32mok\033[0m   %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; failures=$((failures + 1)); }

# A developer's shell may carry these; the tests must not see them.
unset MUVISS_SUPABASE_TARGET MUVISS_PROJECT_REF SUPABASE_PROJECT_REF MUVISS_I_MEAN_PROD MUVISS_DEVICE

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
CALLS="$WORK/calls.log"
BIN="$WORK/bin"
mkdir -p "$BIN"

# adb: every call is a device call, and every device call is a failure here.
cat > "$BIN/adb" <<'EOF'
#!/bin/bash
echo "adb $*" >> "$CALLS"
exit 1
EOF

# supabase: only `status -o json` is used on the local target.
cat > "$BIN/supabase" <<'EOF'
#!/bin/bash
echo "supabase $*" >> "$CALLS"
[ -n "${SUPA_DOWN:-}" ] && exit 1
if [ "$*" = "status -o json" ]; then
  echo '{"API_URL":"http://127.0.0.1:54321","SERVICE_ROLE_KEY":"svc-key","ANON_KEY":"anon-key"}'
  exit 0
fi
exit 1
EOF

# curl: routes on the URL and answers with the status the test sets. With -w it
# prints the body and then the status, as the helper in lib.sh expects. Its
# arguments carry keys, so only the route is logged.
cat > "$BIN/curl" <<'EOF'
#!/bin/bash
args="$*"
case "$args" in
  *"/auth/v1/admin/users"*) route=auth-admin; code=${CURL_ADMIN:-200}; body='{"id":"x"}' ;;
  *"/auth/v1/token"*) route=auth-token; code=${CURL_TOKEN_CODE:-200}; body=${CURL_TOKEN_BODY-'{"access_token":"tok.ABC.def"}'} ;;
  *"-X DELETE"*) route=rest-delete; code=${CURL_DELETE:-204}; body='' ;;
  *"episode_progress?select"*) route=rest-page; code=${CURL_PAGE_CODE:-200}; body=${CURL_PAGE:-[]} ;;
  *"/rest/v1/"*) route=rest-write; code=${CURL_WRITE:-201}; body='' ;;
  *) route=other; code=200; body='' ;;
esac
echo "curl $route" >> "$CALLS"
case "$args" in
  *-w*) printf '%s\n%s' "$body" "$code" ;;
  *) printf '%s' "$body" ;;
esac
EOF
chmod +x "$BIN"/*

# Runs one script from the repo root against the stubs, with MUVISS_SUPABASE_TARGET
# set to $1 (empty for the default). Sets RC and OUT (stdout and stderr) and
# clears the call log first. Environment set by the caller reaches the stubs.
run() {
  local target="$1" script="$2"; shift 2
  : > "$CALLS"
  OUT="$(cd "$REPO_ROOT" && env PATH="$BIN:/usr/bin:/bin" ADB="$BIN/adb" CALLS="$CALLS" \
      ${target:+MUVISS_SUPABASE_TARGET=$target} bash "$SYNC/$script" "$@" 2>&1)"
  RC=$?
}

# Sources lib.sh with the stubs on the path, on the local target, and runs the
# snippet $1 in it. Sets RC and OUT the same way.
lib() {
  : > "$CALLS"
  OUT="$(cd "$REPO_ROOT" && env PATH="$BIN:/usr/bin:/bin" ADB="$BIN/adb" CALLS="$CALLS" \
      MUVISS_SUPABASE_TARGET=local bash -c ". scripts/sync/lib.sh; $1" 2>&1)"
  RC=$?
}

no_calls() { [ ! -s "$CALLS" ]; }

echo "device scripts refuse the local stack, before any call"

run local verify-migration.sh --force
if [ "$RC" -ne 0 ] && grep -q "needs an Android device" <<<"$OUT"; then
  pass "verify-migration refuses local, even with --force"
else fail "verify-migration local --force: rc=$RC out=$OUT"; fi
if no_calls; then pass "verify-migration local makes no adb, supabase or curl call"
else fail "verify-migration local called: $(cat "$CALLS")"; fi

run local verify-migration.sh
if grep -q "needs an Android device" <<<"$OUT" && no_calls; then
  pass "verify-migration refuses local before the --force check"
else fail "verify-migration local without --force: out=$OUT calls=$(cat "$CALLS")"; fi

run local sync-status.sh
if [ "$RC" -ne 0 ] && grep -q "needs an Android device" <<<"$OUT" && grep -q "verify-rls.sh" <<<"$OUT"; then
  pass "sync-status refuses local and names the scripts to run instead"
else fail "sync-status local: rc=$RC out=$OUT"; fi
if no_calls; then pass "sync-status local makes no adb, supabase or curl call"
else fail "sync-status local called: $(cat "$CALLS")"; fi

run local diagnose-signin.sh
if [ "$RC" -ne 0 ] && grep -q "needs an Android device" <<<"$OUT" && no_calls; then
  pass "diagnose-signin refuses local before its first request"
else fail "diagnose-signin local: rc=$RC out=$OUT calls=$(cat "$CALLS")"; fi

echo "hosted targets are not refused"

run "" verify-migration.sh
if [ "$RC" -eq 1 ] && grep -q "wipes the app's data" <<<"$OUT" && no_calls; then
  pass "verify-migration on the default target still asks for --force first"
else fail "verify-migration default: rc=$RC out=$OUT calls=$(cat "$CALLS")"; fi

echo "a local session that cannot be created fails closed"

D="$WORK/s-down"; mkdir -p "$D"
SUPA_DOWN=1 lib "pull_database '$D'"
if [ "$RC" -ne 0 ] && [ ! -e "$D/muviss.db" ]; then pass "stack not running: pull_database fails, no database written"
else fail "stack down: rc=$RC db=$(ls "$D")"; fi

D="$WORK/s-422"; mkdir -p "$D"
CURL_ADMIN=422 lib "pull_database '$D'"
if [ "$RC" -ne 0 ] && [ ! -e "$D/muviss.db" ]; then pass "admin create refused: pull_database fails, no database written"
else fail "admin 422: rc=$RC db=$(ls "$D")"; fi

D="$WORK/s-notoken"; mkdir -p "$D"
CURL_TOKEN_BODY='{"error":"invalid_grant"}' CURL_TOKEN_CODE=400 lib "pull_database '$D'"
if [ "$RC" -ne 0 ] && [ ! -e "$D/muviss.db" ]; then pass "no access token: pull_database fails, no database written"
else fail "no token: rc=$RC db=$(ls "$D")"; fi

D="$WORK/s-ok"; mkdir -p "$D"
lib "db=\$(pull_database '$D') && access_token \"\$db\""
if [ "$RC" -eq 0 ] && [ "$OUT" = "tok.ABC.def" ]; then pass "happy path: the token is in the stand-in database, and nothing else is printed"
else fail "happy path: rc=$RC out=$OUT"; fi

echo "a failed cleanup never reads as cleaned up"

lib "ANON=a; TOKEN=t; probe_cleanup 'http://x/rest/v1/collection_entry' 'media_id=eq.probe'"
if [ "$RC" -eq 0 ] && grep -q "cleaned up: collection_entry" <<<"$OUT"; then pass "a 204 delete reports cleaned up"
else fail "delete 204: rc=$RC out=$OUT"; fi

CURL_DELETE=503 lib "ANON=a; TOKEN=t; probe_cleanup 'http://x/rest/v1/collection_entry' 'media_id=eq.probe'"
if [ "$RC" -ne 0 ] && grep -q "CLEANUP FAILED (HTTP 503)" <<<"$OUT" && ! grep -q "cleaned up" <<<"$OUT"; then
  pass "a 503 delete reports failure, says rows may remain, and prints the filter"
else fail "delete 503: rc=$RC out=$OUT"; fi

CURL_PAGE_CODE=503 CURL_PAGE='<html>upstream</html>' CURL_DELETE=503 run local verify-pagination.sh
if [ "$RC" -ne 0 ] && grep -q "may remain" <<<"$OUT" && ! grep -q "cleaned up" <<<"$OUT"; then
  pass "verify-pagination with a refused page and a refused cleanup fails and says rows may remain"
else fail "pagination 503s: rc=$RC out=$OUT"; fi

CURL_WRITE=503 run local verify-pagination.sh
if [ "$RC" -ne 0 ] && grep -q "not every probe row was inserted" <<<"$OUT"; then
  pass "verify-pagination with refused inserts fails"
else fail "pagination refused inserts: rc=$RC out=$OUT"; fi

echo "every sync script parses"

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
