#!/bin/bash
# Row Level Security: can an unauthenticated caller holding the anon key read
# or write anyone's rows?
#
# The anon key ships inside the app, so it is public by construction. RLS is
# the only thing standing between it and every user's library. Worth re-running
# after any schema change, because a table added without its policy is readable
# by anyone who installs the app.
#
# Reading the answer: an empty array from an anonymous read only means something
# if the table holds a row the anon key ought to be unable to see. A table with
# no rows returns [] whether or not RLS is on, so the check would pass with no
# protection in place (#1024). Each table therefore gets a probe row written as
# the signed-in device user, which the owner must be able to read back, and the
# anon read must not return. A 200 with rows in it is the leak.
#
# Cowatch rows address a second account (recipient_id references auth.users, and
# cannot be the author), so those two tables need a peer id: RLS_PEER_USER_ID,
# or on the local stack a peer created through the local admin API.
set -u
cd "$(dirname "$0")/../.." || exit 1
# shellcheck source=scripts/sync/lib.sh
. scripts/sync/lib.sh

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

DB=$(pull_database "$WORK") || exit 1
TOKEN=$(access_token "$DB")
[ -n "$TOKEN" ] || { echo "nobody is signed in on the device, so there is no owner to probe as — sign in first"; exit 1; }
REST=$(rest_url) || { echo "could not work out the REST URL"; exit 1; }
ANON=$(anon_key) || { echo "could not read the anon key"; exit 1; }
[ -n "$ANON" ] || { echo "could not read the anon key"; exit 1; }

fail=0
MARK="rls-probe-$(python3 -c 'import secrets;print(secrets.token_hex(6))')"

# Every table the app syncs. The pgTAP suite (supabase/tests/database/
# sync_rls.test.sql) checks the same policies from inside, with several real
# accounts, on every PR; this is the check that the *hosted* project matches.
TABLES="collection_entry episode_progress media_list list_entry triage_decision episode_play
triage_snooze taste_profile cowatch_link cowatch_pool_entry"

# The column that selects a table's probe row. Every probe row carries $MARK in it.
probe_column() {
  case $1 in
    collection_entry|triage_decision|triage_snooze|cowatch_pool_entry) echo media_id ;;
    episode_progress) echo episode_id ;;
    media_list|episode_play|taste_profile) echo id ;;
    list_entry) echo list_id ;;
    cowatch_link) echo nonce ;;
  esac
}

# One row, shaped as the app writes it. $2 is the peer account id (cowatch only).
probe_row() {
  local m=$MARK p=$2
  case $1 in
    collection_entry) printf '{"media_id":"%s","media_type":"MOVIE","title":"%s","production_status":"RELEASED","added_at_epoch_ms":1,"updated_at_epoch_ms":1}' "$m" "$m" ;;
    episode_progress) printf '{"episode_id":"%s","media_id":"%s","season_number":1,"episode_number":1,"seen":false,"updated_at_epoch_ms":1}' "$m" "$m" ;;
    media_list) printf '{"id":"%s","name":"%s","created_at_epoch_ms":1,"updated_at_epoch_ms":1}' "$m" "$m" ;;
    list_entry) printf '{"list_id":"%s","media_id":"%s","added_at_epoch_ms":1,"updated_at_epoch_ms":1}' "$m" "$m" ;;
    triage_decision) printf '{"media_id":"%s","media_type":"MOVIE","verdict":"SKIP","title":"%s","decided_at_epoch_ms":1,"resolved":true,"updated_at_epoch_ms":1}' "$m" "$m" ;;
    episode_play) printf '{"id":"%s","episode_id":"%s","media_id":"%s","watched_at_epoch_ms":1,"updated_at_epoch_ms":1}' "$m" "$m" "$m" ;;
    triage_snooze) printf '{"media_id":"%s","media_type":"MOVIE","title":"%s","snoozed_at_epoch_ms":1,"due_at_epoch_day":1,"updated_at_epoch_ms":1}' "$m" "$m" ;;
    taste_profile) printf '{"id":"%s","kind":"genre","value":"%s","label":"%s","updated_at_epoch_ms":1}' "$m" "$m" "$m" ;;
    cowatch_link) [ -n "$p" ] && printf '{"recipient_id":"%s","state":"LINKED","nonce":"%s","created_at_epoch_ms":1,"updated_at_epoch_ms":1}' "$p" "$m" ;;
    cowatch_pool_entry) [ -n "$p" ] && printf '{"recipient_id":"%s","media_id":"%s","media_type":"MOVIE","title":"%s","updated_at_epoch_ms":1}' "$p" "$m" "$m" ;;
  esac
}

# A second account for the cowatch tables. Local only creates one; hosted needs the operator to name one.
peer_user_id() {
  if [ -n "${RLS_PEER_USER_ID:-}" ]; then echo "$RLS_PEER_USER_ID"; return 0; fi
  local_target || return 1
  local api svc email pw
  api=$(local_status API_URL) || return 1
  svc=$(local_status SERVICE_ROLE_KEY) || return 1
  email="rls-peer-$(python3 -c 'import secrets;print(secrets.token_hex(6))')@muviss.test"
  pw=$(python3 -c 'import secrets;print(secrets.token_urlsafe(24))')
  http_call -X POST "$api/auth/v1/admin/users" \
    -H "apikey: $svc" -H "Authorization: Bearer $svc" -H "Content-Type: application/json" \
    -d "{\"email\":\"$email\",\"password\":\"$pw\",\"email_confirm\":true}" || return 1
  printf '%s' "$HTTP_BODY" | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])" 2>/dev/null
}

PEER=""
if ! PEER=$(peer_user_id); then PEER=""; fi

echo "=== per table: anon read (expect: []), owner read (expect: its probe row) ==="
for t in $TABLES; do
  col=$(probe_column "$t")
  filter="$col=eq.$MARK"
  wrote=0
  body=$(probe_row "$t" "$PEER")
  if [ -z "$body" ]; then
    printf '%-18s <-- no probe row: cowatch tables need RLS_PEER_USER_ID (another account id), so this table is not verified\n' "$t"
    fail=1
  elif http_call -X POST "$REST/$t" -H "apikey: $ANON" -H "Authorization: Bearer $TOKEN" \
      -H "Content-Type: application/json" -H "Prefer: return=minimal" -d "$body"; then
    wrote=1
  else
    printf '%-18s <-- the device user could not write its probe row (HTTP %s), so this table is not verified\n' "$t" "$HTTP_CODE"
    fail=1
  fi

  if http_call "$REST/$t?select=*" -H "apikey: $ANON"; then
    anon_body=$(printf '%s' "$HTTP_BODY" | head -c 80)
    printf '%-18s anon HTTP %s  %s' "$t" "$HTTP_CODE" "$anon_body"
    if [ "$anon_body" = "[]" ]; then echo "  OK"; else echo "  <-- LEAK"; fail=1; fi
  else
    printf '%-18s anon HTTP %s  <-- READ FAILED, so this table is not verified\n' "$t" "$HTTP_CODE"; fail=1
  fi

  if [ "$wrote" -eq 1 ]; then
    if http_call "$REST/$t?select=*&$filter" -H "apikey: $ANON" -H "Authorization: Bearer $TOKEN"; then
      n=$(printf '%s' "$HTTP_BODY" | python3 -c "import sys,json;print(len(json.load(sys.stdin)))" 2>/dev/null || echo -1)
      if [ "$n" -ge 1 ]; then
        printf '%-18s owner sees its probe row\n' "$t"
      else
        printf '%-18s <-- the owner cannot read back its probe row, so the anon result above proves nothing\n' "$t"; fail=1
      fi
    else
      printf '%-18s <-- the owner read failed (HTTP %s), so this table is not verified\n' "$t" "$HTTP_CODE"; fail=1
    fi
    probe_cleanup "$REST/$t" "$filter" || fail=1
  fi
done

echo
echo "=== unauthenticated writes (expect: refused by RLS, SQLSTATE 42501) ==="
# Any 4xx is not proof: a NOT NULL, check or foreign-key violation is a 4xx too,
# and would hide a policy that let the row through. Postgres checks privileges
# and RLS before constraints (and foreign keys run later still, as triggers),
# so a refusal for the right reason always carries 42501, whatever the ids in
# the payload. An anonymous caller has no real account ids to send anyway.
probe_write() {
  local table=$1 json=$2
  if http_call -X POST "$REST/$table" -H "apikey: $ANON" -H "Content-Type: application/json" -d "$json"; then
    printf 'POST %-18s -> HTTP %s' "$table" "$HTTP_CODE"
    echo "  <-- ACCEPTED, policy is wrong"; fail=1
  elif [ "$HTTP_CODE" = 000 ]; then
    printf 'POST %-18s -> no response' "$table"
    echo "  <-- could not reach the server, so this table is not verified"; fail=1
  elif printf '%s' "$HTTP_BODY" | grep -q '"42501"'; then
    printf 'POST %-18s -> HTTP %s' "$table" "$HTTP_CODE"
    echo "  OK"
  else
    printf 'POST %-18s -> HTTP %s' "$table" "$HTTP_CODE"
    echo "  <-- refused, but not by RLS: $(printf '%s' "$HTTP_BODY" | head -c 120)"; fail=1
  fi
}
probe_write collection_entry \
  '{"media_id":"rls-probe","media_type":"MOVIE","title":"probe","production_status":"RELEASED","added_at_epoch_ms":1,"updated_at_epoch_ms":1}'
probe_write cowatch_pool_entry \
  '{"user_id":"00000000-0000-0000-0000-000000000000","recipient_id":"00000000-0000-0000-0000-000000000000","media_id":"rls-probe","media_type":"MOVIE","title":"probe","updated_at_epoch_ms":1}'

echo
[ "$fail" -eq 0 ] && echo "RLS looks correct." || echo "RLS FAILED — do not ship this."
exit "$fail"
