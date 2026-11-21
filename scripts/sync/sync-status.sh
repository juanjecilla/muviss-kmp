#!/bin/bash
# Side-by-side row counts, device against server, plus the sync state.
#
# The first thing to run when sync "isn't working": it separates "nothing was
# pushed" from "everything was pushed and the UI is wrong", which look
# identical from inside the app.
#
# A row still marked dirty is one the device believes it has not pushed. Dirty
# rows that are already on the server mean a push succeeded and the clear did
# not; rows on neither side mean the push never happened.
set -u
cd "$(dirname "$0")/../.." || exit 1
# shellcheck source=scripts/sync/lib.sh
. scripts/sync/lib.sh
# Reads the device's own tables, which the local stack's stand-in does not have.
refuse_local sync-status

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# A failed pull stops here. Carrying on would read an empty database, report
# "signed out" and exit 0, which looks exactly like a real sign-out.
DB=$(pull_database "$WORK") || exit 1

echo "=== session ==="
sqlite3 "$DB" "SELECT CASE WHEN accessToken IS NULL THEN 'signed out'
  ELSE 'signed in as '||COALESCE(email,'(no email)')||'  backend='||COALESCE(backendId,'?')||'  expires='||COALESCE(expiresAtEpochMs,'-') END
  FROM syncAccount;"

echo
echo "=== sync state ==="
# Two different timestamps on purpose (ADR 0018): the cursor is server-stamped
# and drives the next pull; lastSynced is local and only says when a cycle ran.
sqlite3 "$DB" "SELECT 'pull cursor : '||COALESCE(CAST(syncCursorEpochMs AS TEXT),'never pulled')||CHAR(10)||
                      'last synced : '||COALESCE(CAST(lastSyncedAtEpochMs AS TEXT),'never') FROM appSettings WHERE id=0;"

TOKEN=$(access_token "$DB")
if [ -z "$TOKEN" ]; then
  echo
  echo "(signed out — skipping the server side)"
  exit 0
fi
ANON=$(anon_key) || { echo "could not read the anon key" >&2; exit 1; }
REST=$(rest_url) || { echo "could not work out the REST URL" >&2; exit 1; }

# "?" when the server did not answer 2xx, so a refused request never reads as zero.
remote_count() {
  if ! http_call "$REST/$1?select=*" -o /dev/null -D "$WORK/headers" \
       -H "apikey: $ANON" -H "Authorization: Bearer $TOKEN" \
       -H "Prefer: count=exact" -H "Range: 0-0"; then
    echo "?"; return
  fi
  grep -i '^content-range:' "$WORK/headers" | sed -E 's|.*/||' | tr -d '\r' | head -1 | grep . || echo "?"
}

echo
printf '%-18s %8s %8s %8s\n' "table" "device" "dirty" "server"
paste_row() { printf '%-18s %8s %8s %8s\n' "$1" "$2" "$3" "$4"; }
paste_row collectionEntry  "$(sqlite3 "$DB" 'SELECT COUNT(*) FROM collectionEntry;')"  "$(sqlite3 "$DB" 'SELECT COUNT(*) FROM collectionEntry WHERE isDirty=1;')"  "$(remote_count collection_entry)"
paste_row episodeProgress  "$(sqlite3 "$DB" 'SELECT COUNT(*) FROM episodeProgress;')"  "$(sqlite3 "$DB" 'SELECT COUNT(*) FROM episodeProgress WHERE isDirty=1;')"  "$(remote_count episode_progress)"
paste_row episodePlay      "$(sqlite3 "$DB" 'SELECT COUNT(*) FROM episodePlay;')"      "$(sqlite3 "$DB" 'SELECT COUNT(*) FROM episodePlay WHERE isDirty=1;')"      "$(remote_count episode_play)"
paste_row triageDecision   "$(sqlite3 "$DB" 'SELECT COUNT(*) FROM triageDecision;')"   "$(sqlite3 "$DB" 'SELECT COUNT(*) FROM triageDecision WHERE isDirty=1;')"   "$(remote_count triage_decision)"
paste_row mediaList        "$(sqlite3 "$DB" 'SELECT COUNT(*) FROM mediaList;')"        "$(sqlite3 "$DB" 'SELECT COUNT(*) FROM mediaList WHERE isDirty=1;')"        "$(remote_count media_list)"
paste_row listEntry        "$(sqlite3 "$DB" 'SELECT COUNT(*) FROM listEntry;')"        "$(sqlite3 "$DB" 'SELECT COUNT(*) FROM listEntry WHERE isDirty=1;')"        "$(remote_count list_entry)"

echo
echo "note: device counts include soft-deleted rows; the app's own screens filter deleted = 0."
