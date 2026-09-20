#!/bin/bash
# Does a JSON null in a push body actually clear the column on the server?
#
# EPIC 39's defect 3: the client used to omit nulls from the body, and PostgREST
# builds `ON CONFLICT DO UPDATE SET` from the union of the keys in the body, so a
# key absent from every object is not written and the old value stayed. The fix
# is to send `"rating":null`. This is the one-curl check of both halves, against
# the live project: an omitted key must leave the value alone (which is why the
# fix was needed) and an explicit null must clear it (which is why it works).
#
# The unit tests assert this against FakeSupabaseServer, which is a model of
# PostgREST written from its documentation. This is the check that the model is
# right. See issue #88.
#
# Uses a synthetic media_id and hard-deletes it afterwards. Needs a signed-in device.
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
MEDIA="tmdb:movie:000000-null-probe"

upsert() { # $1 updated_at, $2 json members after updated_at
  curl -s -o /dev/null -w '%{http_code}' -X POST "$REST" \
    -H "apikey: $ANON" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -H "Prefer: resolution=merge-duplicates,return=minimal" \
    -d "[{\"media_id\":\"$MEDIA\",\"media_type\":\"MOVIE\",\"title\":\"probe\",\"production_status\":\"RELEASED\",\"added_at_epoch_ms\":1,\"updated_at_epoch_ms\":$1$2}]"
}
rating() {
  curl -s "$REST?media_id=eq.$MEDIA&select=rating" -H "apikey: $ANON" -H "Authorization: Bearer $TOKEN" \
    | python3 -c "import sys,json;d=json.load(sys.stdin);print(d[0]['rating'] if d else '<none>')"
}

fail=0
NOW_MS=$(python3 -c "import time;print(int(time.time()*1000))")
T1=$((NOW_MS - 30000)); T2=$((NOW_MS - 20000)); T3=$((NOW_MS - 10000))

echo "1. seed rating 8           -> HTTP $(upsert $T1 ',"rating":8'), rating: $(rating)"

upsert $T2 '' >/dev/null
got=$(rating)
printf '2. newer write, no rating key  -> rating: %s' "$got"
if [ "$got" = "8" ]; then echo "  (left alone: this is the bug the client used to have)"; else echo "  <-- an omitted key changed the column; the premise of the fix is wrong"; fail=1; fi

upsert $T3 ',"rating":null' >/dev/null
got=$(rating)
printf '3. newer write, "rating":null  -> rating: %s' "$got"
if [ "$got" = "None" ] || [ "$got" = "null" ]; then echo "  OK (cleared)"; else echo "  <-- an explicit null did not clear it"; fail=1; fi

curl -s -o /dev/null -X DELETE "$REST?media_id=eq.$MEDIA" -H "apikey: $ANON" -H "Authorization: Bearer $TOKEN"
echo
exit "$fail"
