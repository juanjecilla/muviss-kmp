#!/bin/bash
# Does discard_stale_write() actually fire on the merge-duplicates upsert path?
#
# This is the single assumption SyncEngine's ordering rests on. It pushes
# unconditionally, with no read-before-write, *because* the server is supposed
# to drop a write whose updated_at_epoch_ms is older than what is already
# stored (ADR 0009, docs/SYNC.md). If the trigger does not fire on this path,
# two devices quietly stop converging and nothing anywhere reports an error.
#
# The check that matters is step 2: the client gets a 2xx either way, because
# the trigger rewrites NEW back to OLD rather than raising. So "the push
# succeeded" tells you nothing — only re-reading the row does.
#
# Uses a synthetic media_id and hard-deletes it afterwards, so no real library
# data is touched. Needs a signed-in device: the trigger only fires under a
# policy that requires a real user.
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
MEDIA="tmdb:movie:000000-trigger-probe"

upsert() { # $1 title, $2 updated_at
  curl -s -o /dev/null -w '%{http_code}' -X POST "$REST" \
    -H "apikey: $ANON" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -H "Prefer: resolution=merge-duplicates,return=minimal" \
    -d "[{\"media_id\":\"$MEDIA\",\"media_type\":\"MOVIE\",\"title\":\"$1\",\"production_status\":\"RELEASED\",\"added_at_epoch_ms\":1,\"updated_at_epoch_ms\":$2}]"
}
stored() {
  curl -s "$REST?media_id=eq.$MEDIA&select=title" -H "apikey: $ANON" -H "Authorization: Bearer $TOKEN" \
    | python3 -c "import sys,json;d=json.load(sys.stdin);print(d[0]['title'] if d else '<none>')"
}

fail=0

echo "1. seed at t=2000                -> HTTP $(upsert ORIGINAL 2000), stored: $(stored)"

code=$(upsert STALE 1000)
got=$(stored)
printf '2. stale write at t=1000         -> HTTP %s, stored: %s' "$code" "$got"
if [ "$got" = "ORIGINAL" ]; then echo "  OK (discarded)"; else echo "  <-- STALE WRITE APPLIED"; fail=1; fi

code=$(upsert NEWER 3000)
got=$(stored)
printf '3. newer write at t=3000         -> HTTP %s, stored: %s' "$code" "$got"
if [ "$got" = "NEWER" ]; then echo "  OK (applied)"; else echo "  <-- NEWER WRITE REJECTED"; fail=1; fi

curl -s -o /dev/null -X DELETE "$REST?media_id=eq.$MEDIA" -H "apikey: $ANON" -H "Authorization: Bearer $TOKEN"
echo "4. cleanup                       -> stored: $(stored)"

echo
[ "$fail" -eq 0 ] && echo "Last-write-wins trigger is working." || echo "TRIGGER FAILED — devices will diverge silently."
exit "$fail"
