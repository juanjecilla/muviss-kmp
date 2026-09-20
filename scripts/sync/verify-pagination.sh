#!/bin/bash
# Does paging by server_seq return everything, whatever max_rows the project has?
#
# EPIC 39's defect 1: the pull used to be one GET with no limit, and Supabase
# cuts a response at `max_rows` with a 200 and no marker. The client now pages
# `order=server_seq.asc&limit=500&server_seq=gt.<n>` until an EMPTY page, because
# the hosted project's max_rows is not known (supabase/config.toml says 1000 for
# local; the dashboard's Data API settings say what the project really has).
#
# This inserts N synthetic rows, pages through them exactly as the client does,
# reports the largest page the server ever returned (that is the effective
# max_rows if it is below the requested limit), and checks every row came back.
# See issue #88. Hard-deletes what it inserted. Needs a signed-in device.
#
#   N=1500 scripts/sync/verify-pagination.sh
set -u
cd "$(dirname "$0")/../.." || exit 1
# shellcheck source=scripts/sync/lib.sh
. scripts/sync/lib.sh

N="${N:-1500}"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

DB=$(pull_database "$WORK")
TOKEN=$(access_token "$DB")
[ -n "$TOKEN" ] || { echo "nobody is signed in on the device — sign in first"; exit 1; }
ANON=$(anon_key)
REST="$(rest_url)/episode_progress"
PREFIX="tmdb:tv:000000-page-probe"

python3 - "$N" "$PREFIX" > "$WORK/body.json" <<'PY'
import json, sys, time
n, prefix = int(sys.argv[1]), sys.argv[2]
now = int(time.time() * 1000) - 60000
print(json.dumps([{"episode_id": f"{prefix}/1/{i}", "media_id": prefix, "season_number": 1,
                   "episode_number": i, "seen": True, "updated_at_epoch_ms": now} for i in range(1, n + 1)]))
PY

# Chunked, as the client is: 500 per request.
python3 - "$WORK/body.json" "$WORK" <<'PY'
import json, sys
rows = json.load(open(sys.argv[1]))
for i in range(0, len(rows), 500):
    json.dump(rows[i:i + 500], open(f"{sys.argv[2]}/chunk-{i // 500}.json", "w"))
PY
for chunk in "$WORK"/chunk-*.json; do
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$REST" \
    -H "apikey: $ANON" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -H "Prefer: resolution=merge-duplicates,return=minimal" --data-binary "@$chunk")
  echo "insert $(basename "$chunk") -> HTTP $code"
done

seen=0; largest=0; after=0; pages=0
while :; do
  page=$(curl -s "$REST?select=episode_id,server_seq&media_id=eq.$PREFIX&order=server_seq.asc&limit=500&server_seq=gt.$after" \
    -H "apikey: $ANON" -H "Authorization: Bearer $TOKEN")
  read -r count last < <(printf '%s' "$page" | python3 -c "import sys,json;d=json.load(sys.stdin);print(len(d), d[-1]['server_seq'] if d else 0)")
  [ "$count" -eq 0 ] && break
  pages=$((pages + 1)); seen=$((seen + count)); after=$last
  [ "$count" -gt "$largest" ] && largest=$count
done
echo "paged $seen of $N rows in $pages pages; the largest page was $largest"
[ "$largest" -lt 500 ] && echo "  (a page smaller than the requested 500 that was not the last means the project's max_rows is $largest)"

curl -s -o /dev/null -X DELETE "$REST?media_id=eq.$PREFIX" -H "apikey: $ANON" -H "Authorization: Bearer $TOKEN"
if [ "$seen" -eq "$N" ]; then echo "OK: every row came back"; exit 0; else echo "FAIL: $((N - seen)) rows were never returned"; exit 1; fi
