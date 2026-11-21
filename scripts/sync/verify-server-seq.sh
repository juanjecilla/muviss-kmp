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

DB=$(pull_database "$WORK") || exit 1
TOKEN=$(access_token "$DB")
[ -n "$TOKEN" ] || { echo "nobody is signed in on the device — sign in first"; exit 1; }
ANON=$(anon_key) || { echo "could not read the anon key"; exit 1; }
REST=$(rest_url) || { echo "could not work out the REST URL"; exit 1; }
REST="$REST/collection_entry"
A="tmdb:movie:000000-seq-probe-a"
B="tmdb:movie:000000-seq-probe-b"

upsert() { # $1 media_id, $2 updated_at, $3 extra json members (optional, leading comma). Prints the status; returns 0 only on 2xx.
  local rc=0
  http_call -X POST "$REST" \
    -H "apikey: $ANON" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -H "Prefer: resolution=merge-duplicates,return=minimal" \
    -d "[{\"media_id\":\"$1\",\"media_type\":\"MOVIE\",\"title\":\"probe\",\"production_status\":\"RELEASED\",\"added_at_epoch_ms\":1,\"updated_at_epoch_ms\":$2${3:-}}]" || rc=$?
  echo "$HTTP_CODE"
  return "$rc"
}
# A write the server refused is a failed probe, not a silent no-op.
write() { # same arguments as upsert
  local code rc=0
  code=$(upsert "$@") || rc=$?
  if [ "$rc" -ne 0 ]; then echo "  <-- write refused (HTTP $code)"; fail=1; fi
}
field() { # $1 media_id, $2 column. A failed read prints a marker that is not an integer.
  if ! http_call "$REST?media_id=eq.$1&select=$2" -H "apikey: $ANON" -H "Authorization: Bearer $TOKEN"; then
    echo "<read failed: HTTP $HTTP_CODE>"; return
  fi
  printf '%s' "$HTTP_BODY" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d[0]['$2'] if d else '<none>')" 2>/dev/null \
    || echo "<unreadable>"
}
is_int() { case "$1" in ''|*[!0-9]*) return 1 ;; esac; }

fail=0
check() { # $1 label, $2 condition result (0 = ok)
  if [ "$2" -eq 0 ]; then echo "  OK   $1"; else echo "  FAIL $1"; fail=1; fi
}

NOW_MS=$(python3 -c "import time;print(int(time.time()*1000))")

write "$A" 1000
write "$B" 1000
SA=$(field "$A" server_seq); SB=$(field "$B" server_seq)
echo "1. two inserts: a=$SA b=$SB"
is_int "$SA" && is_int "$SB" && [ "$SB" -gt "$SA" ]; check "b was stamped after a" $?

write "$A" 2000
SA2=$(field "$A" server_seq)
echo "2. newer write to a: server_seq $SA -> $SA2"
is_int "$SA" && is_int "$SA2" && [ "$SA2" -gt "$SA" ]; check "a newer write takes a new server_seq" $?

write "$A" 1500
SA3=$(field "$A" server_seq)
echo "3. stale write to a: server_seq $SA2 -> $SA3"
is_int "$SA2" && is_int "$SA3" && [ "$SA3" = "$SA2" ]; check "a discarded write keeps its old server_seq" $?

write "$B" $((NOW_MS + 86400000))
CLAMPED=$(field "$B" updated_at_epoch_ms)
LIMIT=$((NOW_MS + 90000))
echo "4. b stamped a day ahead: stored updated_at_epoch_ms=$CLAMPED (limit $LIMIT)"
is_int "$CLAMPED" && [ "$CLAMPED" -le "$LIMIT" ]; check "the future timestamp was clamped" $?

write "$B" $((NOW_MS + 1)) ',"server_seq":1'
SB2=$(field "$B" server_seq)
echo "5. b sent with server_seq=1: stored server_seq=$SB2"
is_int "$SB2" && is_int "$SA3" && [ "$SB2" -gt "$SA3" ]; check "a client-supplied server_seq is overwritten" $?

for id in "$A" "$B"; do
  probe_cleanup "$REST" "media_id=eq.$id" || fail=1
done
echo
if [ "$fail" -eq 0 ]; then echo "server_seq behaves as the client assumes"; else echo "server_seq does NOT behave as the client assumes — do not enable sync"; fi
exit "$fail"
