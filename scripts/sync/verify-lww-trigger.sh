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

DB=$(pull_database "$WORK") || exit 1
TOKEN=$(access_token "$DB")
[ -n "$TOKEN" ] || { echo "nobody is signed in on the device — sign in first"; exit 1; }
ANON=$(anon_key) || { echo "could not read the anon key"; exit 1; }
REST=$(rest_url) || { echo "could not work out the REST URL"; exit 1; }
REST="$REST/collection_entry"
MEDIA="tmdb:movie:000000-trigger-probe"

upsert() { # $1 title, $2 updated_at. Prints the status; returns 0 only on 2xx.
  local rc=0
  http_call -X POST "$REST" \
    -H "apikey: $ANON" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -H "Prefer: resolution=merge-duplicates,return=minimal" \
    -d "[{\"media_id\":\"$MEDIA\",\"media_type\":\"MOVIE\",\"title\":\"$1\",\"production_status\":\"RELEASED\",\"added_at_epoch_ms\":1,\"updated_at_epoch_ms\":$2}]" || rc=$?
  echo "$HTTP_CODE"
  return "$rc"
}
stored() { # Prints the stored title. A failed read prints a marker that matches no expected title.
  if ! http_call "$REST?media_id=eq.$MEDIA&select=title" -H "apikey: $ANON" -H "Authorization: Bearer $TOKEN"; then
    echo "<read failed: HTTP $HTTP_CODE>"; return
  fi
  printf '%s' "$HTTP_BODY" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d[0]['title'] if d else '<none>')" 2>/dev/null \
    || echo "<unreadable>"
}

fail=0

code=$(upsert ORIGINAL 2000); rc=$?
seeded=$(stored)
echo "1. seed at t=2000                -> HTTP $code, stored: $seeded"
[ "$rc" -eq 0 ] || { echo "  <-- the seed write was refused"; fail=1; }
# The stale step below proves nothing unless the baseline row is really there.
[ "$seeded" = ORIGINAL ] || { echo "  <-- the seed did not land, so the stale write has no baseline"; fail=1; }

code=$(upsert STALE 1000); rc=$?
got=$(stored)
printf '2. stale write at t=1000         -> HTTP %s, stored: %s' "$code" "$got"
if [ "$rc" -eq 0 ] && [ "$got" = "ORIGINAL" ]; then echo "  OK (discarded)"; else echo "  <-- STALE WRITE APPLIED, OR THE WRITE WAS REFUSED"; fail=1; fi

code=$(upsert NEWER 3000); rc=$?
got=$(stored)
printf '3. newer write at t=3000         -> HTTP %s, stored: %s' "$code" "$got"
if [ "$rc" -eq 0 ] && [ "$got" = "NEWER" ]; then echo "  OK (applied)"; else echo "  <-- NEWER WRITE REJECTED, OR THE WRITE WAS REFUSED"; fail=1; fi

probe_cleanup "$REST" "media_id=eq.$MEDIA" || fail=1
echo "4. after cleanup                 -> stored: $(stored)"

echo
[ "$fail" -eq 0 ] && echo "Last-write-wins trigger is working." || echo "TRIGGER FAILED — devices will diverge silently."
exit "$fail"
