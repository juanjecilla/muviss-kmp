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
# Every table the app syncs. The pgTAP suite (supabase/tests/database/
# sync_rls.test.sql) checks the same policies from inside, with several real
# accounts, on every PR; this is the check that the *hosted* project matches.
TABLES="collection_entry episode_progress media_list list_entry triage_decision episode_play
triage_snooze cowatch_link cowatch_pool_entry"
for t in $TABLES; do
  code=$(curl -s -o /dev/null -w '%{http_code}' "$REST/$t?select=*" -H "apikey: $ANON")
  body=$(curl -s "$REST/$t?select=*" -H "apikey: $ANON" | head -c 80)
  printf '%-18s HTTP %s  %s' "$t" "$code" "$body"
  if [ "$body" = "[]" ]; then echo "  OK"; else echo "  <-- LEAK"; fail=1; fi
done

echo
echo "=== unauthenticated writes (expect: refused by RLS, SQLSTATE 42501) ==="
# Any 4xx is not proof: a NOT NULL, check or foreign-key violation is a 4xx too,
# and would hide a policy that let the row through. Postgres checks privileges
# and RLS before constraints (and foreign keys run later still, as triggers),
# so a refusal for the right reason always carries 42501, whatever the ids in
# the payload. An anonymous caller has no real account ids to send anyway.
probe_write() {
  local table=$1 json=$2 out code
  out=$(curl -s -w '\n%{http_code}' -X POST "$REST/$table" \
    -H "apikey: $ANON" -H "Content-Type: application/json" -d "$json")
  code=${out##*$'\n'}
  printf 'POST %-18s -> HTTP %s' "$table" "$code"
  if [ "$code" -ge 400 ] && printf '%s' "$out" | grep -q '"42501"'; then
    echo "  OK"
  elif [ "$code" -ge 400 ]; then
    echo "  <-- refused, but not by RLS: $(printf '%s' "${out%$'\n'*}" | head -c 120)"; fail=1
  else
    echo "  <-- ACCEPTED, policy is wrong"; fail=1
  fi
}
probe_write collection_entry \
  '{"media_id":"rls-probe","media_type":"MOVIE","title":"probe","production_status":"RELEASED","added_at_epoch_ms":1,"updated_at_epoch_ms":1}'
probe_write cowatch_pool_entry \
  '{"user_id":"00000000-0000-0000-0000-000000000000","recipient_id":"00000000-0000-0000-0000-000000000000","media_id":"rls-probe","media_type":"MOVIE","title":"probe","updated_at_epoch_ms":1}'

echo
[ "$fail" -eq 0 ] && echo "RLS looks correct." || echo "RLS FAILED — do not ship this."
exit "$fail"
