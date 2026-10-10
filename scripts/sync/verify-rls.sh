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
#
# Since EPIC 32 (migration 20261010000100) anon holds no table privilege at
# all, so the expected read answer becomes a 401 with PostgREST code 42501
# ("permission denied for table"). Both are accepted as OK: a project that has
# not applied that migration yet still answers 200 []. `entitlement` (ADR 0019)
# is checked too — anon must neither read nor write it.
set -u
cd "$(dirname "$0")/../.." || exit 1
# shellcheck source=scripts/sync/lib.sh
. scripts/sync/lib.sh

REST=$(rest_url)
ANON=$(anon_key)
[ -n "$ANON" ] || { echo "could not read the anon key"; exit 1; }

fail=0

echo "=== unauthenticated reads (expect: 200 with [], or 401 permission denied) ==="
for t in collection_entry episode_progress media_list list_entry triage_decision episode_play \
         triage_snooze cowatch_link cowatch_pool_entry entitlement; do
  code=$(curl -s -o /dev/null -w '%{http_code}' "$REST/$t?select=*" -H "apikey: $ANON")
  body=$(curl -s "$REST/$t?select=*" -H "apikey: $ANON" | head -c 80)
  printf '%-18s HTTP %s  %s' "$t" "$code" "$body"
  if [ "$body" = "[]" ]; then echo "  OK"
  elif [ "$code" = "401" ] && printf '%s' "$body" | grep -q '42501'; then echo "  OK (no privilege)"
  else echo "  <-- LEAK"; fail=1; fi
done

echo
echo "=== unauthenticated write (expect: refused) ==="
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$REST/collection_entry" \
  -H "apikey: $ANON" -H "Content-Type: application/json" \
  -d '{"media_id":"rls-probe","media_type":"MOVIE","title":"probe","production_status":"RELEASED","added_at_epoch_ms":1,"updated_at_epoch_ms":1}')
printf 'POST collection_entry -> HTTP %s' "$code"
if [ "$code" -ge 400 ]; then echo "  OK"; else echo "  <-- ACCEPTED, policy is wrong"; fail=1; fi

# Only the revenuecat-webhook Edge Function (service role) may write this.
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$REST/entitlement" \
  -H "apikey: $ANON" -H "Content-Type: application/json" \
  -d '{"user_id":"00000000-0000-0000-0000-000000000000","active":true}')
printf 'POST entitlement      -> HTTP %s' "$code"
if [ "$code" -ge 400 ]; then echo "  OK"; else echo "  <-- ACCEPTED, anyone can grant themselves sync"; fail=1; fi

echo
[ "$fail" -eq 0 ] && echo "RLS looks correct." || echo "RLS FAILED — do not ship this."
exit "$fail"
