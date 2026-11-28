#!/bin/bash
# Shared helpers for the sync verification scripts. Source this, don't run it.
#
# Nothing here prints a token or a key. The scripts read the signed-in session
# straight off the device because that is the only way to exercise the server
# as the app actually does — but a credential that reaches stdout ends up in a
# terminal buffer, a screenshot, or a pasted bug report, so it never does.

set -u

: "${ADB:=$HOME/Library/Android/sdk/platform-tools/adb}"
: "${PKG:=com.codingpit.muviss}"

# ---------------------------------------------------------------- device ---

# Picks the device to talk to. With more than one attached, adb refuses to
# guess, so set MUVISS_DEVICE (e.g. MUVISS_DEVICE=emulator-5554) rather than
# unplugging things. A phone plugged in for charging is enough to break these
# scripts otherwise.
# Prints the serial to use, or nothing for adb's own default. Exits when the
# choice is ambiguous; adb_ calls it with `|| exit 1`, because an exit inside a
# command substitution would only leave the subshell and the adb call would run
# anyway (#1023).
adb_serial() {
  if [ -n "${MUVISS_DEVICE:-}" ]; then
    echo "$MUVISS_DEVICE"
    return
  fi
  local count
  count=$("$ADB" devices | grep -cE '\sdevice$' || true)
  if [ "$count" -gt 1 ]; then
    echo "More than one device is attached. Set MUVISS_DEVICE to one of:" >&2
    "$ADB" devices | grep -E '\sdevice$' | awk '{print "  " $1}' >&2
    return 1
  fi
  echo ""
}

# Runs adb against the chosen device. No array: this file runs under bash 3.2 on
# macOS, where an empty "${arr[@]}" under set -u aborts.
adb_() {
  local serial
  serial="$(adb_serial)" || exit 1
  if [ -n "$serial" ]; then
    "$ADB" -s "$serial" "$@"
  else
    "$ADB" "$@"
  fi
}

# ---------------------------------------------------------------- database ---

# Copies the app's SQLite database off the device and echoes the local path.
#
# Pulls the -wal and -shm sidecars too. Without them a recent write — a session
# that was just stored, say — is still in the write-ahead log and simply is not
# in the .db file yet, which reads as "no such table" or silently stale data.
pull_database() {
  local out="${1:?usage: pull_database <dir>}"
  mkdir -p "$out"
  if local_target; then
    local_session_db "$out" \
      || { echo "could not create the local test session (is \`supabase start\` running?)" >&2; exit 1; }
    echo "$out/muviss.db"
    return
  fi
  local f
  for f in muviss.db muviss.db-wal muviss.db-shm; do
    adb_ exec-out run-as "$PKG" cat "databases/$f" > "$out/$f" 2>/dev/null || true
    [ -s "$out/$f" ] || rm -f "$out/$f"
  done
  [ -s "$out/muviss.db" ] || { echo "could not read the app database (is it installed and debuggable?)" >&2; exit 1; }
  echo "$out/muviss.db"
}

# ---------------------------------------------------------------- supabase ---

# Which project these scripts talk to (EPIC 43, #151). Dev unless asked
# otherwise: several of them create rows, and the only project they used to
# know was production. Never the CLI's linked project — `supabase link` is
# global state that a `db push` in another terminal also reads, so "whatever
# is linked" is how a check meant for dev lands on prod.
#
#   MUVISS_SUPABASE_TARGET=prod MUVISS_I_MEAN_PROD=yes scripts/sync/...   # prod, deliberately
#   MUVISS_SUPABASE_TARGET=local scripts/sync/...                       # the stack `supabase start` runs (#287)
#   SUPABASE_PROJECT_REF=<ref> scripts/sync/...                         # anything else
MUVISS_DEV_REF="mnleklzanxxpvmakrbxv"   # "Muviss dev"
MUVISS_PROD_REF="sodjedenvnvsuktbxevt"  # "Muviss app" — real users' data
# Resolved once, when this file is sourced, so a refusal exits the script
# itself: an `exit` inside `$(project_ref)` would only end that substitution,
# and the caller would go on with `https://.supabase.co`. The prod check runs
# on the resolved ref, so an inherited SUPABASE_PROJECT_REF naming prod needs
# the same confirmation as MUVISS_SUPABASE_TARGET=prod.
resolve_project_ref() {
  local ref
  if [ -n "${SUPABASE_PROJECT_REF:-}" ]; then
    ref="$SUPABASE_PROJECT_REF"
  else
    case "${MUVISS_SUPABASE_TARGET:-dev}" in
      dev) ref="$MUVISS_DEV_REF" ;;
      prod) ref="$MUVISS_PROD_REF" ;;
      local) ref="local" ;;
      *) echo "MUVISS_SUPABASE_TARGET must be dev, prod or local" >&2; return 1 ;;
    esac
  fi
  if [ "$ref" = "$MUVISS_PROD_REF" ] && [ "${MUVISS_I_MEAN_PROD:-}" != yes ]; then
    echo "refusing prod without MUVISS_I_MEAN_PROD=yes (these scripts write test rows)" >&2
    return 1
  fi
  MUVISS_PROJECT_REF="$ref"
}
resolve_project_ref || exit 1
project_ref() { echo "$MUVISS_PROJECT_REF"; }

# ------------------------------------------------------------------- http ---

# Every curl whose status matters goes through here. curl exits 0 on a 503, so
# a bare curl cannot say whether a write landed: a script that prints "cleaned
# up" after one has lied when the server refused the DELETE.
#
# Runs curl with the arguments given and records HTTP_CODE and HTTP_BODY. Returns
# 0 only for a 2xx status, or when curl itself failed, which counts as 000.
# Never echo its arguments (they carry the keys) or the body (it can echo them).
http_call() {
  local out
  if ! out=$(curl -s -w '\n%{http_code}' "$@"); then
    HTTP_CODE=000; HTTP_BODY=""; return 1
  fi
  HTTP_CODE=${out##*$'\n'}
  HTTP_BODY=${out%$'\n'*}
  case "$HTTP_CODE" in 2??) return 0 ;; *) return 1 ;; esac
}

# Deletes probe rows and says so only on a verified 2xx. $1 is the table's REST
# URL, $2 the PostgREST filter that selects exactly the probe rows. Needs ANON and
# TOKEN in the caller. Prints the table and filter, never the host or the keys,
# so the message is enough to run the DELETE by hand.
probe_cleanup() {
  local rest=$1 filter=$2 table
  table=${rest##*/}
  if http_call -X DELETE "$rest?$filter" -H "apikey: $ANON" -H "Authorization: Bearer $TOKEN"; then
    echo "  cleaned up: $table where $filter"
  else
    echo "  CLEANUP FAILED (HTTP $HTTP_CODE): probe rows may remain in $table." >&2
    echo "  retry with: DELETE $table where $filter" >&2
    return 1
  fi
}

# ------------------------------------------------------------------ local ---

# The local stack (`supabase start`, from this repo's config). There is no
# device, so the "signed-in session" is a throwaway account created through the
# local admin API, and its access token goes into a stand-in database with the
# one table `access_token` reads. Only ever the local API URL, never a hosted one.
#
# Which scripts run against it: the ones that talk to the server only —
# verify-rls, verify-lww-trigger, verify-null-clearing, verify-pagination,
# verify-server-seq. Those read nothing from the database but the token.
# The scripts that need a phone (sync-status, verify-migration, diagnose-signin)
# call refuse_local and stop before they touch anything.
local_target() { [ "$MUVISS_PROJECT_REF" = local ]; }

# For the scripts that need a device. verify-migration in particular runs
# `pm clear` on whatever phone is attached, so this must run before any adb_.
#
# $2 is an optional hint naming what to run instead.
refuse_local() {
  if local_target; then
    echo "$1 needs an Android device, and MUVISS_SUPABASE_TARGET=local has none. Run it with the default target and the device attached." >&2
    [ -n "${2:-}" ] && echo "$2" >&2
    exit 1
  fi
}

# One field of `supabase status`, e.g. local_status API_URL. Fails, and prints
# nothing, when the stack is not running or the field is missing.
local_status() {
  local v
  v=$(supabase status -o json 2>/dev/null \
    | python3 -c "import sys,json;print(json.load(sys.stdin)['$1'])" 2>/dev/null) || return 1
  [ -n "$v" ] || return 1
  echo "$v"
}

# Creates a fresh local account, signs it in, and writes the token into
# $1/muviss.db. Returns non-zero, and writes no database, when any step fails:
# a missing token would otherwise read as "signed out" and exit 0.
# Nothing is printed: the password and token stay in the process.
local_session_db() {
  local out=$1 api svc anon email pw tok
  api=$(local_status API_URL) || return 1
  svc=$(local_status SERVICE_ROLE_KEY) || return 1
  anon=$(local_status ANON_KEY) || return 1
  email="sync-probe-$(python3 -c 'import secrets;print(secrets.token_hex(6))')@muviss.test"
  pw=$(python3 -c 'import secrets;print(secrets.token_urlsafe(24))')
  http_call -X POST "$api/auth/v1/admin/users" \
    -H "apikey: $svc" -H "Authorization: Bearer $svc" -H "Content-Type: application/json" \
    -d "{\"email\":\"$email\",\"password\":\"$pw\",\"email_confirm\":true}" || return 1
  http_call -X POST "$api/auth/v1/token?grant_type=password" \
    -H "apikey: $anon" -H "Content-Type: application/json" \
    -d "{\"email\":\"$email\",\"password\":\"$pw\"}" || return 1
  tok=$(printf '%s' "$HTTP_BODY" | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])" 2>/dev/null) || return 1
  [ -n "$tok" ] || return 1
  rm -f "$out/muviss.db"
  sqlite3 "$out/muviss.db" "CREATE TABLE syncAccount(accessToken TEXT); INSERT INTO syncAccount VALUES('$tok');" || return 1
}

anon_key() {
  if local_target; then
    local_status ANON_KEY
    return
  fi
  local key
  key=$(supabase projects api-keys --project-ref "$(project_ref)" -o json 2>/dev/null \
    | python3 -c "import sys,json;print(next(k['api_key'] for k in json.load(sys.stdin) if k.get('name')=='anon'))" 2>/dev/null) || return 1
  [ -n "$key" ] || return 1
  echo "$key"
}

# The access token of whoever is signed in on the device. Empty when signed out.
access_token() {
  local db="${1:?usage: access_token <db>}"
  sqlite3 "$db" "SELECT COALESCE(accessToken,'') FROM syncAccount;" 2>/dev/null
}

rest_url() {
  if local_target; then
    local api
    api=$(local_status API_URL) || return 1
    echo "$api/rest/v1"
    return
  fi
  echo "https://$(project_ref).supabase.co/rest/v1"
}
