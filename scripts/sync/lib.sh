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
adb_device_flag() {
  if [ -n "${MUVISS_DEVICE:-}" ]; then
    echo "-s $MUVISS_DEVICE"
    return
  fi
  local count
  count=$("$ADB" devices | grep -cE '\sdevice$' || true)
  if [ "$count" -gt 1 ]; then
    echo "More than one device is attached. Set MUVISS_DEVICE to one of:" >&2
    "$ADB" devices | grep -E '\sdevice$' | awk '{print "  " $1}' >&2
    exit 1
  fi
  echo ""
}

# shellcheck disable=SC2086
adb_() { "$ADB" $(adb_device_flag) "$@"; }

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
    local_session_db "$out"
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

# ------------------------------------------------------------------ local ---

# The local stack (`supabase start`, from this repo's config). The scripts run
# unchanged against it: there is no device, so the "signed-in session" is a
# throwaway account created through the local admin API, and its access token
# goes into a stand-in database with the one table `access_token` reads. Only
# ever the local API URL, never a hosted one.
local_target() { [ "$MUVISS_PROJECT_REF" = local ]; }

# One field of `supabase status`, e.g. local_status API_URL.
local_status() {
  supabase status -o json 2>/dev/null \
    | python3 -c "import sys,json;print(json.load(sys.stdin)['$1'])"
}

# Creates a fresh local account, signs it in, and writes the token into
# $1/muviss.db. Nothing is printed: the password and token stay in the process.
local_session_db() {
  local out=$1 api svc anon email pw tok
  api=$(local_status API_URL); svc=$(local_status SERVICE_ROLE_KEY); anon=$(local_status ANON_KEY)
  email="sync-probe-$(python3 -c 'import secrets;print(secrets.token_hex(6))')@muviss.test"
  pw=$(python3 -c 'import secrets;print(secrets.token_urlsafe(24))')
  curl -s -o /dev/null -X POST "$api/auth/v1/admin/users" \
    -H "apikey: $svc" -H "Authorization: Bearer $svc" -H "Content-Type: application/json" \
    -d "{\"email\":\"$email\",\"password\":\"$pw\",\"email_confirm\":true}"
  tok=$(curl -s -X POST "$api/auth/v1/token?grant_type=password" \
    -H "apikey: $anon" -H "Content-Type: application/json" \
    -d "{\"email\":\"$email\",\"password\":\"$pw\"}" \
    | python3 -c "import sys,json;print(json.load(sys.stdin).get('access_token',''))")
  rm -f "$out/muviss.db"
  sqlite3 "$out/muviss.db" "CREATE TABLE syncAccount(accessToken TEXT); INSERT INTO syncAccount VALUES('$tok');"
}

anon_key() {
  if local_target; then
    local_status ANON_KEY
    return
  fi
  supabase projects api-keys --project-ref "$(project_ref)" -o json 2>/dev/null \
    | python3 -c "import sys,json;print(next(k['api_key'] for k in json.load(sys.stdin) if k.get('name')=='anon'))"
}

# The access token of whoever is signed in on the device. Empty when signed out.
access_token() {
  local db="${1:?usage: access_token <db>}"
  sqlite3 "$db" "SELECT COALESCE(accessToken,'') FROM syncAccount;" 2>/dev/null
}

rest_url() {
  if local_target; then
    echo "$(local_status API_URL)/rest/v1"
    return
  fi
  echo "https://$(project_ref).supabase.co/rest/v1"
}
