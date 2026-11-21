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

upsert() { # $1 media_id, $2 title, $3 updated_at, $4 extra json members (optional, leading comma). Prints the status; returns 0 only on 2xx.
  local rc=0
  http_call -X POST "$REST" \
    -H "apikey: $ANON" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -H "Prefer: resolution=merge-duplicates,return=minimal" \
    -d "[{\"media_id\":\"$1\",\"media_type\":\"MOVIE\",\"title\":\"$2\",\"production_status\":\"RELEASED\",\"added_at_epoch_ms\":1,\"updated_at_epoch_ms\":$3${4:-}}]" || rc=$?
  echo "$HTTP_CODE"
  return "$rc"
}
# A write the server refused is a failed probe, not a silent no-op.
write() { # same arguments as upsert
  local code rc=0
  code=$(upsert "$@") || rc=$?
  if [ "$rc" -ne 0 ]; then echo "  <-- write refused (HTTP $code)"; fail=1; fi
  return "$rc"
}
# Prints "title server_seq updated_at_epoch_ms" for a media_id, read in one request.
# A failed read prints markers that match no expected value.
row() { # $1 media_id
  if ! http_call "$REST?media_id=eq.$1&select=title,server_seq,updated_at_epoch_ms" -H "apikey: $ANON" -H "Authorization: Bearer $TOKEN"; then
    echo "READ_FAILED - -"; return
  fi
  printf '%s' "$HTTP_BODY" | python3 -c "import sys,json;d=json.load(sys.stdin);r=d[0] if d else {};print(r.get('title','MISSING'), r.get('server_seq','MISSING'), r.get('updated_at_epoch_ms','MISSING'))" 2>/dev/null \
    || echo "UNREADABLE - -"
}
is_int() { case "$1" in ''|*[!0-9]*) return 1 ;; esac; }

fail=0
# check LABEL COMMAND... : the command's status is the verdict. Conditions are
# written inline, not passed as $?, so a later edit cannot check a stale status.
check() {
  local label=$1; shift
  if "$@"; then echo "  OK   $label"; else echo "  FAIL $label"; fail=1; fi
}
int_gt() { is_int "$1" && is_int "$2" && [ "$1" -gt "$2" ]; }
int_le() { is_int "$1" && [ "$1" -le "$2" ]; }
same() { [ "$1" = "$2" ]; }

NOW_MS=$(python3 -c "import time;print(int(time.time()*1000))")

# Every step reads the row before and after its write, and asserts on the title
# the write sets. A write the trigger discarded still answers 2xx and leaves the
# old row in place, so "the seq did not move" alone cannot tell a discard from a
# write that never landed. The title can: each write carries a title nothing else
# uses, and a step only passes when its own title is on the row (accepted), or
# when a stale write's title is absent and the seq did not move (discarded).

write "$A" a1 1000; write "$B" b1 1000
read -r TA SA _ <<< "$(row "$A")"; read -r TB SB _ <<< "$(row "$B")"
echo "1. two inserts: a=$SA ($TA) b=$SB ($TB)"
check "a insert landed" same "$TA" a1
check "b insert landed" same "$TB" b1
check "b was stamped after a" int_gt "$SB" "$SA"

write "$A" a2 2000
read -r TA2 SA2 _ <<< "$(row "$A")"
echo "2. newer write to a: server_seq $SA -> $SA2 (title $TA2)"
check "the newer write landed" same "$TA2" a2
check "a newer write takes a new server_seq" int_gt "$SA2" "$SA"

write "$A" stale 1500   # 2xx, but stamped older than the stored row
read -r TA3 SA3 _ <<< "$(row "$A")"
echo "3. stale write to a: server_seq $SA2 -> $SA3 (title $TA3)"
check "the stale write was discarded (old title kept)" same "$TA3" a2
check "a discarded write keeps its old server_seq" same "$SA3" "$SA2"

# The clamp: a stamp a day ahead is accepted, but stored at most a minute ahead.
write "$B" clamp $((NOW_MS + 86400000))
read -r TB4 SB4 UB4 <<< "$(row "$B")"
LIMIT=$((NOW_MS + 90000))
echo "4. b stamped a day ahead: title $TB4, stored updated_at_epoch_ms=$UB4 (limit $LIMIT)"
check "the future-stamped write landed" same "$TB4" clamp
check "the future timestamp was clamped" int_le "$UB4" "$LIMIT"

# A client-sent server_seq. The write carries the stored stamp exactly, so it is
# not older than the row and is accepted; the trigger must then overwrite the
# client's server_seq:1 with a fresh one from the sequence.
write "$B" client "$UB4" ',"server_seq":1'
read -r TB5 SB5 _ <<< "$(row "$B")"
echo "5. b sent with server_seq=1: title $TB5, server_seq $SB4 -> $SB5"
check "the write carrying a client server_seq landed" same "$TB5" client
check "a client-supplied server_seq is overwritten" int_gt "$SB5" "$SB4"

for id in "$A" "$B"; do
  probe_cleanup "$REST" "media_id=eq.$id" || fail=1
done
echo
if [ "$fail" -eq 0 ]; then echo "server_seq behaves as the client assumes"; else echo "server_seq does NOT behave as the client assumes — do not enable sync"; fi
exit "$fail"
