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

# adb: device calls fail, except the two sync-status needs on a device: the device
# list (one attached) and the app database, copied from FAKE_DEVICE_DB.
cat > "$BIN/adb" <<'EOF'
#!/bin/bash
echo "adb $*" >> "$CALLS"
case "$*" in
  devices) printf 'List of devices attached\nemulator-5554\tdevice\n'
    [ -n "${FAKE_TWO_DEVICES:-}" ] && printf 'emulator-5556\tdevice\n' ;;
  *"exec-out run-as"*"databases/muviss.db")
    [ -n "${FAKE_DEVICE_DB:-}" ] && cat "$FAKE_DEVICE_DB" || exit 1 ;;
  *) exit 1 ;;
esac
EOF

# supabase: `status -o json` on the local target, `projects api-keys` on hosted ones.
cat > "$BIN/supabase" <<'EOF'
#!/bin/bash
echo "supabase $*" >> "$CALLS"
[ -n "${SUPA_DOWN:-}" ] && exit 1
case "$*" in
  "status -o json")
    echo '{"API_URL":"http://127.0.0.1:54321","SERVICE_ROLE_KEY":"svc-key","ANON_KEY":"anon-key"}' ;;
  "projects api-keys"*)
    echo '[{"name":"anon","api_key":"anon-key"}]' ;;
  *) exit 1 ;;
esac
EOF

# curl: routes on the URL and answers with the status the test sets. With -w it
# prints the body and then the status, as the helper in lib.sh expects. A count
# request writes its Content-Range into the -D file. Its arguments carry keys, so
# only the route is logged.
cat > "$BIN/curl" <<'EOF'
#!/bin/bash
args="$*"
headers=""
prev=""
for a in "$@"; do
  [ "$prev" = "-D" ] && headers="$a"
  prev="$a"
done
case "$args" in
  *"/auth/v1/admin/users"*) route=auth-admin; code=${CURL_ADMIN:-200}; body='{"id":"x"}' ;;
  *"/auth/v1/token"*) route=auth-token; code=${CURL_TOKEN_CODE:-200}; body=${CURL_TOKEN_BODY-'{"access_token":"tok.ABC.def"}'} ;;
  *"count=exact"*)
    route=rest-count; code=${CURL_COUNT:-200}; body=''
    [ -n "$headers" ] && printf 'Content-Range: 0-0/%s\r\n' "${CURL_COUNT_TOTAL:-7}" > "$headers" ;;
  *"-X DELETE"*) route=rest-delete; code=${CURL_DELETE:-204}; body='' ;;
  *"select=title,rating"*) route=rest-row; code=200; body=${CURL_ROW:-'[{"title":"seed","rating":8}]'} ;;
  *"select=*"*) route=rest-read; code=${CURL_READ_CODE:-200}; body=${CURL_READ:-[]} ;;
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

echo "sync-status lists every synced table, and never prints a blank cell"

status="$SYNC/sync-status.sh"
for t in collectionEntry episodeProgress episodePlay triageDecision mediaList listEntry triageSnooze tasteProfile; do
  if grep -q "^paste_row $t " "$status"; then pass "sync-status has a row for $t"
  else fail "sync-status has no row for $t"; fi
done
if grep -q "FROM syncCursor" "$status" && ! grep -q "SELECT.*syncCursorEpochMs" "$status"; then
  pass "sync-status reads the per-table syncCursor, not the dead appSettings column"
else fail "sync-status still reads appSettings.syncCursorEpochMs"; fi
if ! grep -qE '^paste_row .*\$\(sqlite3' "$status"; then
  pass "no device count bypasses db_count, so none can print blank"
else fail "a device count in sync-status is not routed through db_count"; fi

# A device with every table sync-status reads. Counts: 5 rows, 2 dirty, per table.
DEVICE_DB="$WORK/device.db"
{
  echo "CREATE TABLE syncAccount(accessToken TEXT, email TEXT, backendId TEXT, expiresAtEpochMs INTEGER);"
  echo "INSERT INTO syncAccount VALUES('tok.ABC.def','a@b.test','backend-1',1);"
  echo "CREATE TABLE appSettings(id INTEGER PRIMARY KEY, lastSyncedAtEpochMs INTEGER);"
  echo "INSERT INTO appSettings VALUES(0, 1234);"
  echo "CREATE TABLE syncCursor(tableName TEXT PRIMARY KEY, seq INTEGER);"
  echo "INSERT INTO syncCursor VALUES('collection_entry', 42);"
  for t in collectionEntry episodeProgress episodePlay triageDecision mediaList listEntry triageSnooze tasteProfile; do
    echo "CREATE TABLE $t(id TEXT, isDirty INTEGER);"
    echo "INSERT INTO $t VALUES('a',1),('b',1),('c',0),('d',0),('e',0);"
  done
} | sqlite3 "$DEVICE_DB"

# Rows of the device table: a name followed by three cells, each a count or "?".
table_rows() { grep -E '^(collectionEntry|episodeProgress|episodePlay|triageDecision|mediaList|listEntry|triageSnooze|tasteProfile) ' <<<"$OUT"; }

FAKE_DEVICE_DB="$DEVICE_DB" CURL_COUNT_TOTAL=7 run "" sync-status.sh
if [ "$RC" -eq 0 ] && grep -q "last synced : 1234" <<<"$OUT" && grep -q "  collection_entry = 42" <<<"$OUT"; then
  pass "the cursor and the last-sync time are printed"
else fail "cursor and last sync: rc=$RC out=$OUT"; fi

if [ "$(table_rows | wc -l | tr -d ' ')" -eq 8 ] && [ "$(table_rows | grep -cE ' +5 +2 +7$')" -eq 8 ]; then
  pass "all eight synced tables are listed, with device, dirty and server counts"
else fail "table listing: $(table_rows)"; fi

FAKE_DEVICE_DB="$DEVICE_DB" CURL_COUNT=503 run "" sync-status.sh
if [ "$(table_rows | grep -cE ' +5 +2 +\?$')" -eq 8 ]; then
  pass "a refused server count prints ? in every row, not a blank"
else fail "refused server counts: $(table_rows)"; fi

# A table the device does not have: its cells say ?, and the rest still print.
MISSING_TABLE_DB="$WORK/device-no-snooze.db"
cp "$DEVICE_DB" "$MISSING_TABLE_DB"
sqlite3 "$MISSING_TABLE_DB" "DROP TABLE triageSnooze;"
FAKE_DEVICE_DB="$MISSING_TABLE_DB" run "" sync-status.sh
if grep -qE '^triageSnooze +\? +\? +7$' <<<"$OUT" && grep -qE '^collectionEntry +5 +2 +7$' <<<"$OUT"; then
  pass "an unreadable device count prints ?, and the other rows are unchanged"
else fail "missing device table: $(table_rows)"; fi

NO_CURSOR_DB="$WORK/device-no-cursor.db"
cp "$DEVICE_DB" "$NO_CURSOR_DB"
sqlite3 "$NO_CURSOR_DB" "DROP TABLE syncCursor;"
FAKE_DEVICE_DB="$NO_CURSOR_DB" run "" sync-status.sh
if grep -q "^  ?$" <<<"$OUT"; then pass "an unreadable cursor table prints ?, not never pulled"
else fail "missing cursor table: out=$OUT"; fi

# The reserved `_lastFullPullAt` row is not a cursor: it is left out of the list,
# and a device with only that row has never pulled a table, so it says none.
RESERVED_DB="$WORK/device-reserved-and-cursor.db"
cp "$DEVICE_DB" "$RESERVED_DB"
sqlite3 "$RESERVED_DB" "INSERT INTO syncCursor VALUES('_lastFullPullAt', 1700000000000);"
FAKE_DEVICE_DB="$RESERVED_DB" run "" sync-status.sh
if grep -q "  collection_entry = 42" <<<"$OUT" && ! grep -q "_lastFullPullAt" <<<"$OUT"; then
  pass "a real cursor is listed and the reserved _lastFullPullAt row is not"
else fail "reserved row beside a cursor: out=$OUT"; fi

RESERVED_ONLY_DB="$WORK/device-reserved-only.db"
cp "$DEVICE_DB" "$RESERVED_ONLY_DB"
sqlite3 "$RESERVED_ONLY_DB" "DELETE FROM syncCursor; INSERT INTO syncCursor VALUES('_lastFullPullAt', 1700000000000);"
FAKE_DEVICE_DB="$RESERVED_ONLY_DB" run "" sync-status.sh
if [ "$RC" -eq 0 ] && grep -q "^  none$" <<<"$OUT" && ! grep -q "_lastFullPullAt" <<<"$OUT"; then
  pass "only the reserved row present: the list says none, as for a device that never pulled"
else fail "reserved row only: rc=$RC out=$OUT"; fi

echo "adb with two devices and no MUVISS_DEVICE stops the script"
FAKE_TWO_DEVICES=1 lib "adb_ shell true; echo after-adb"
if [ "$RC" -ne 0 ] && ! grep -q "after-adb" <<<"$OUT" && ! grep -q "adb shell" "$CALLS"; then
  pass "the ambiguity exits the script before any adb call"
else fail "two devices: rc=$RC out=$OUT calls=$(cat "$CALLS")"; fi

echo "verify-null-clearing checks the seed before it trusts the rest"
CURL_ROW='[{"title":"old-title","rating":null}]' run local verify-null-clearing.sh
if [ "$RC" -ne 0 ] && grep -q "the seed did not land" <<<"$OUT"; then
  pass "a seed that did not land (stale probe row) fails and says so"
else fail "stale probe row: rc=$RC out=$OUT"; fi
CURL_ROW='[{"title":"seed","rating":8}]' run local verify-null-clearing.sh
if ! grep -q "the seed did not land" <<<"$OUT"; then
  pass "a seed that landed prints no seed warning"
else fail "landed seed warned: out=$OUT"; fi

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
