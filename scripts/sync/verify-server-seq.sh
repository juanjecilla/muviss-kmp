#!/bin/bash
# Does the server hand out a change sequence the way the client's pull assumes?
#
# EPIC 39 (ADR 0020) moved the pull off "the newest client timestamp I have
# seen" and onto `server_seq`, stamped by a trigger. That trigger is the whole
# feature: if it does not fire on the merge-duplicates upsert path the client
# uses, or a discarded stale write still moves its row, incremental pulls
# silently miss changes. This checks it on the live project, after
# `supabase db push` has applied 20260920000000_sync_server_seq.sql.
#
#   1. two inserts get strictly increasing server_seq
#   2. a newer write moves the row's server_seq
#   3. a STALE write leaves the row, and its server_seq, alone
#   4. a timestamp a day ahead is clamped to about a minute ahead
#   5. a server_seq sent by the client is ignored
#
# Uses synthetic media_ids and hard-deletes them afterwards. Needs a signed-in
# device (the trigger only fires under a policy that requires a real user).
set -u
cd "$(dirname "$0")/../.." || exit 1
# shellcheck source=scripts/sync/lib.sh
. scripts/sync/lib.sh

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

DB=$(pull_database "$WORK")
TOKEN=$(access_token "$DB")
[ -n "$TOKEN" ] || { echo "nobody is signed in on the device — sign in first"; exit 1; }
ANON=$(anon_key)
REST="$(rest_url)/collection_entry"
A="tmdb:movie:000000-seq-probe-a"
B="tmdb:movie:000000-seq-probe-b"

upsert() { # $1 media_id, $2 updated_at, $3 extra json members (optional, leading comma)
  curl -s -o /dev/null -w '%{http_code}' -X POST "$REST" \
    -H "apikey: $ANON" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -H "Prefer: resolution=merge-duplicates,return=minimal" \
    -d "[{\"media_id\":\"$1\",\"media_type\":\"MOVIE\",\"title\":\"probe\",\"production_status\":\"RELEASED\",\"added_at_epoch_ms\":1,\"updated_at_epoch_ms\":$2${3:-}}]"
}
field() { # $1 media_id, $2 column
  curl -s "$REST?media_id=eq.$1&select=$2" -H "apikey: $ANON" -H "Authorization: Bearer $TOKEN" \
    | python3 -c "import sys,json;d=json.load(sys.stdin);print(d[0]['$2'] if d else '<none>')"
}

fail=0
check() { # $1 label, $2 condition result (0 = ok)
  if [ "$2" -eq 0 ]; then echo "  OK   $1"; else echo "  FAIL $1"; fail=1; fi
}

NOW_MS=$(python3 -c "import time;print(int(time.time()*1000))")

upsert "$A" 1000 >/dev/null
upsert "$B" 1000 >/dev/null
SA=$(field "$A" server_seq); SB=$(field "$B" server_seq)
echo "1. two inserts: a=$SA b=$SB"
[ "$SB" -gt "$SA" ] 2>/dev/null; check "b was stamped after a" $?

upsert "$A" 2000 >/dev/null
SA2=$(field "$A" server_seq)
echo "2. newer write to a: server_seq $SA -> $SA2"
[ "$SA2" -gt "$SA" ] 2>/dev/null; check "a newer write takes a new server_seq" $?

upsert "$A" 1500 >/dev/null
SA3=$(field "$A" server_seq)
echo "3. stale write to a: server_seq $SA2 -> $SA3"
[ "$SA3" = "$SA2" ]; check "a discarded write keeps its old server_seq" $?

upsert "$B" $((NOW_MS + 86400000)) >/dev/null
CLAMPED=$(field "$B" updated_at_epoch_ms)
LIMIT=$((NOW_MS + 90000))
echo "4. b stamped a day ahead: stored updated_at_epoch_ms=$CLAMPED (limit $LIMIT)"
[ "$CLAMPED" -le "$LIMIT" ] 2>/dev/null; check "the future timestamp was clamped" $?

upsert "$B" $((NOW_MS + 1)) ',"server_seq":1' >/dev/null
SB2=$(field "$B" server_seq)
echo "5. b sent with server_seq=1: stored server_seq=$SB2"
[ "$SB2" -gt "$SA3" ] 2>/dev/null; check "a client-supplied server_seq is overwritten" $?

for id in "$A" "$B"; do
  curl -s -o /dev/null -X DELETE "$REST?media_id=eq.$id" -H "apikey: $ANON" -H "Authorization: Bearer $TOKEN"
done
echo
if [ "$fail" -eq 0 ]; then echo "server_seq behaves as the client assumes"; else echo "server_seq does NOT behave as the client assumes — do not enable sync"; fi
exit "$fail"
