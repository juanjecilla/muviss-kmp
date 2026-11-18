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
# Refused before anything else. Local mode's pull_database makes a throwaway
# account, so the device columns would have no device behind them: the counts
# would be zero and read as an answer when they are not one.
refuse_local sync-status "Use verify-rls.sh, verify-server-seq.sh, verify-null-clearing.sh, verify-pagination.sh or verify-lww-trigger.sh there instead."

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
# Two different things on purpose (ADR 0018, ADR 0020): the pull cursors are server-assigned
# sequences, one per table, in `syncCursor` (`appSettings.syncCursorEpochMs` has been dead since
# 9.sqm); lastSynced is local and only says when a cycle ran. A table with no cursor row has
# never been pulled. `_lastFullPullAt` is the weekly full pull's mark, not a table.
# A read that fails prints "?", never a blank: a blank cursor list reads as "never pulled".
LAST="$(sqlite3 "$DB" "SELECT COALESCE(CAST(lastSyncedAtEpochMs AS TEXT),'never') FROM appSettings WHERE id=0;")" \
  && echo "last synced : ${LAST:-never}" || echo "last synced : ?"
echo "pull cursors (server_seq per table; none = never pulled):"
if CURSORS="$(sqlite3 "$DB" "SELECT '  '||tableName||' = '||seq FROM syncCursor ORDER BY tableName;")"; then
  if [ -n "$CURSORS" ]; then echo "$CURSORS"; else echo "  none"; fi
else
  echo "  ?"
fi

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

# A device count that cannot be read prints "?", the same rule as the server side.
db_count() { # $1 SQL
  local n
  n="$(sqlite3 "$DB" "$1" 2>/dev/null)" && [ -n "$n" ] && echo "$n" || echo "?"
}

echo
printf '%-18s %8s %8s %8s\n' "table" "device" "dirty" "server"
paste_row() { printf '%-18s %8s %8s %8s\n' "$1" "$2" "$3" "$4"; }
paste_row collectionEntry  "$(db_count 'SELECT COUNT(*) FROM collectionEntry;')"  "$(db_count 'SELECT COUNT(*) FROM collectionEntry WHERE isDirty=1;')"  "$(remote_count collection_entry)"
paste_row episodeProgress  "$(db_count 'SELECT COUNT(*) FROM episodeProgress;')"  "$(db_count 'SELECT COUNT(*) FROM episodeProgress WHERE isDirty=1;')"  "$(remote_count episode_progress)"
paste_row episodePlay      "$(db_count 'SELECT COUNT(*) FROM episodePlay;')"      "$(db_count 'SELECT COUNT(*) FROM episodePlay WHERE isDirty=1;')"      "$(remote_count episode_play)"
paste_row triageDecision   "$(db_count 'SELECT COUNT(*) FROM triageDecision;')"   "$(db_count 'SELECT COUNT(*) FROM triageDecision WHERE isDirty=1;')"   "$(remote_count triage_decision)"
paste_row mediaList        "$(db_count 'SELECT COUNT(*) FROM mediaList;')"        "$(db_count 'SELECT COUNT(*) FROM mediaList WHERE isDirty=1;')"        "$(remote_count media_list)"
paste_row listEntry        "$(db_count 'SELECT COUNT(*) FROM listEntry;')"        "$(db_count 'SELECT COUNT(*) FROM listEntry WHERE isDirty=1;')"        "$(remote_count list_entry)"
paste_row triageSnooze     "$(db_count 'SELECT COUNT(*) FROM triageSnooze;')"     "$(db_count 'SELECT COUNT(*) FROM triageSnooze WHERE isDirty=1;')"     "$(remote_count triage_snooze)"
paste_row tasteProfile     "$(db_count 'SELECT COUNT(*) FROM tasteProfile;')"     "$(db_count 'SELECT COUNT(*) FROM tasteProfile WHERE isDirty=1;')"     "$(remote_count taste_profile)"

echo
echo "note: device counts include soft-deleted rows; the app's own screens filter deleted = 0."
