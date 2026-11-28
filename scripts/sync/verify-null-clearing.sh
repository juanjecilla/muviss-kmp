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

DB=$(pull_database "$WORK") || exit 1
TOKEN=$(access_token "$DB")
[ -n "$TOKEN" ] || { echo "nobody is signed in on the device — sign in first"; exit 1; }
ANON=$(anon_key) || { echo "could not read the anon key"; exit 1; }
REST=$(rest_url) || { echo "could not work out the REST URL"; exit 1; }
REST="$REST/collection_entry"
MEDIA="tmdb:movie:000000-null-probe"

upsert() { # $1 title, $2 updated_at, $3 json members after updated_at. Prints the status; returns 0 only on 2xx.
  local rc=0
  http_call -X POST "$REST" \
    -H "apikey: $ANON" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -H "Prefer: resolution=merge-duplicates,return=minimal" \
    -d "[{\"media_id\":\"$MEDIA\",\"media_type\":\"MOVIE\",\"title\":\"$1\",\"production_status\":\"RELEASED\",\"added_at_epoch_ms\":1,\"updated_at_epoch_ms\":$2$3}]" || rc=$?
  echo "$HTTP_CODE"
  return "$rc"
}
# Prints "title rating", read in one request. A failed read prints a marker that
# matches no expected value, so a check on it cannot pass.
# True when row() could not read the row back at all, as opposed to reading a
# value that differs from the expected one.
read_failed() { [ "$1" = READ_FAILED ] || [ "$1" = UNREADABLE ]; }
row() {
  if ! http_call "$REST?media_id=eq.$MEDIA&select=title,rating" -H "apikey: $ANON" -H "Authorization: Bearer $TOKEN"; then
    echo "READ_FAILED READ_FAILED"; return
  fi
  printf '%s' "$HTTP_BODY" | python3 -c "import sys,json;d=json.load(sys.stdin);r=d[0] if d else {};print(r.get('title','MISSING'), r.get('rating','MISSING'))" 2>/dev/null \
    || echo "UNREADABLE UNREADABLE"
}

fail=0
NOW_MS=$(python3 -c "import time;print(int(time.time()*1000))")
T1=$((NOW_MS - 30000)); T2=$((NOW_MS - 20000)); T3=$((NOW_MS - 10000))

# Each step checks the title as well as the rating. A rating left alone is also
# what a write that never reached the row would leave, so the title is the proof
# that the write landed.
code=$(upsert seed $T1 ',"rating":8'); rc=$?
read -r T R <<< "$(row)"
echo "1. seed rating 8           -> HTTP $code, title: $T, rating: $R"
[ "$rc" -eq 0 ] || { echo "  <-- the seed write was refused"; fail=1; }
# A leftover probe row stamped after T1 makes the trigger discard the seed with
# an HTTP success, so only the values read back prove the seed landed (#1025).
# A failed read proves nothing either way, so it is reported as one.
if [ "$rc" -eq 0 ] && read_failed "$T"; then
  echo "  <-- the seed was written but the read-back failed, so it is unproven"; fail=1
elif [ "$rc" -eq 0 ] && { [ "$T" != seed ] || [ "$R" != 8 ]; }; then
  echo "  <-- the seed did not land (title $T, rating $R); a leftover probe row with a newer timestamp is a possible cause"; fail=1
fi

upsert no-rating-key $T2 '' >/dev/null; rc=$?
read -r T R <<< "$(row)"
printf '2. newer write, no rating key  -> title: %s, rating: %s' "$T" "$R"
if [ "$rc" -ne 0 ]; then echo "  <-- the write was refused"; fail=1
elif read_failed "$T"; then echo "  <-- the read-back failed, so this step proves nothing"; fail=1
elif [ "$T" != "no-rating-key" ]; then echo "  <-- the write did not land, so this step proves nothing"; fail=1
elif [ "$R" = "8" ]; then echo "  (left alone: this is the bug the client used to have)"
else echo "  <-- an omitted key changed the column; the premise of the fix is wrong"; fail=1; fi

upsert null-rating $T3 ',"rating":null' >/dev/null; rc=$?
read -r T R <<< "$(row)"
printf '3. newer write, "rating":null  -> title: %s, rating: %s' "$T" "$R"
if [ "$rc" -ne 0 ]; then echo "  <-- the write was refused"; fail=1
elif read_failed "$T"; then echo "  <-- the read-back failed, so this step proves nothing"; fail=1
elif [ "$T" != "null-rating" ]; then echo "  <-- the write did not land, so this step proves nothing"; fail=1
elif [ "$R" = "None" ] || [ "$R" = "null" ]; then echo "  OK (cleared)"
else echo "  <-- an explicit null did not clear it"; fail=1; fi

probe_cleanup "$REST" "media_id=eq.$MEDIA" || fail=1
echo
[ "$fail" -eq 0 ] && echo "Null clearing is working." || echo "NULL CLEARING FAILED — see above."
exit "$fail"
