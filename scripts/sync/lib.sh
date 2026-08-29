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
  local f
  for f in muviss.db muviss.db-wal muviss.db-shm; do
    adb_ exec-out run-as "$PKG" cat "databases/$f" > "$out/$f" 2>/dev/null || true
    [ -s "$out/$f" ] || rm -f "$out/$f"
  done
  [ -s "$out/muviss.db" ] || { echo "could not read the app database (is it installed and debuggable?)" >&2; exit 1; }
  echo "$out/muviss.db"
}

# ---------------------------------------------------------------- supabase ---

project_ref() {
  local ref_file="supabase/.temp/project-ref"
  if [ -n "${SUPABASE_PROJECT_REF:-}" ]; then echo "$SUPABASE_PROJECT_REF"; return; fi
  [ -f "$ref_file" ] || { echo "not linked — run 'supabase link --project-ref <ref>' first" >&2; exit 1; }
  tr -d '\r\n' < "$ref_file"
}

anon_key() {
  supabase projects api-keys --project-ref "$(project_ref)" -o json 2>/dev/null \
    | python3 -c "import sys,json;print(next(k['api_key'] for k in json.load(sys.stdin) if k.get('name')=='anon'))"
}

# The access token of whoever is signed in on the device. Empty when signed out.
access_token() {
  local db="${1:?usage: access_token <db>}"
  sqlite3 "$db" "SELECT COALESCE(accessToken,'') FROM syncAccount;" 2>/dev/null
}

rest_url() { echo "https://$(project_ref).supabase.co/rest/v1"; }
