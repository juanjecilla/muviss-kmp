#!/bin/bash
# Row Level Security: can an unauthenticated caller holding the anon key read
# or write anyone's rows?
#
# The anon key ships inside the app, so it is public by construction. RLS is
# the only thing standing between it and every user's library. Worth re-running
# after any schema change, because a table added without its policy is readable
# by anyone who installs the app.
#
# Reading the answer: a 200 with an EMPTY array is correct, not a leak.
# auth.uid() is null for an unauthenticated request, so `using (auth.uid() =
# user_id)` matches no rows and PostgREST returns an empty set rather than an
# error. A 200 with rows in it would be the failure.
set -u
cd "$(dirname "$0")/../.." || exit 1
# shellcheck source=scripts/sync/lib.sh
. scripts/sync/lib.sh

REST=$(rest_url)
ANON=$(anon_key)
[ -n "$ANON" ] || { echo "could not read the anon key"; exit 1; }

fail=0

echo "=== unauthenticated reads (expect: 200 with []) ==="
for t in collection_entry episode_progress media_list list_entry triage_decision episode_play; do
  code=$(curl -s -o /dev/null -w '%{http_code}' "$REST/$t?select=*" -H "apikey: $ANON")
  body=$(curl -s "$REST/$t?select=*" -H "apikey: $ANON" | head -c 80)
  printf '%-18s HTTP %s  %s' "$t" "$code" "$body"
  if [ "$body" = "[]" ]; then echo "  OK"; else echo "  <-- LEAK"; fail=1; fi
done

echo
echo "=== unauthenticated write (expect: refused) ==="
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$REST/collection_entry" \
  -H "apikey: $ANON" -H "Content-Type: application/json" \
  -d '{"media_id":"rls-probe","media_type":"MOVIE","title":"probe","production_status":"RELEASED","added_at_epoch_ms":1,"updated_at_epoch_ms":1}')
printf 'POST collection_entry -> HTTP %s' "$code"
if [ "$code" -ge 400 ]; then echo "  OK"; else echo "  <-- ACCEPTED, policy is wrong"; fail=1; fi

echo
[ "$fail" -eq 0 ] && echo "RLS looks correct." || echo "RLS FAILED — do not ship this."
exit "$fail"
