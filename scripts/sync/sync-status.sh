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

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

DB=$(pull_database "$WORK")

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
ANON=$(anon_key)
REST=$(rest_url)

remote_count() {
  curl -s "$REST/$1?select=*" -H "apikey: $ANON" -H "Authorization: Bearer $TOKEN" \
       -H "Prefer: count=exact" -H "Range: 0-0" -D - -o /dev/null \
    | grep -i '^content-range:' | sed -E 's|.*/||' | tr -d '\r'
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
