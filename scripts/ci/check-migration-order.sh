#!/bin/bash
# CI check (#727): a migration this change adds must sort after the newest migration on develop.
#
# supabase/migrations is applied in filename order. A migration that a branch adds with a
# timestamp before one already on develop is applied out of order on any database that has the
# newer one, and a held migration (#283) rots that way with every migration merged before its
# release. So each added file must start with a 14-digit timestamp, and it must be strictly
# after develop's newest. A migration that is only renamed or changed is not an addition, and
# files already on the base are not judged.
#
# Usage: scripts/ci/check-migration-order.sh [base-ref]   (default origin/develop)
# The fix is to rename the file to a timestamp after the one it is told about, and to commit.
set -euo pipefail

BASE="${1:-origin/develop}"
DIR="supabase/migrations"

# A base that cannot be read fails the check: an empty listing would pass every file.
git rev-parse --verify -q "$BASE^{commit}" >/dev/null \
  || { echo "::error::$BASE is not a commit in this checkout; fetch it (fetch-depth: 0)"; exit 1; }
base_files="$(git ls-tree --name-only "$BASE" -- "$DIR/")"
newest_name="$(sed -E 's#.*/##' <<<"$base_files" | grep -E '^[0-9]{14}_.*\.sql$' | sort | tail -1 || true)"
newest_ts="${newest_name%%_*}"

diff_files="$(git diff --name-only --diff-filter=A "$BASE...HEAD" -- "$DIR/")"
added="$(sed -E 's#.*/##' <<<"$diff_files")"
if [ -z "$added" ]; then
  echo "ok: this change adds no migration"
  exit 0
fi

failed=0
while IFS= read -r name; do
  [ -n "$name" ] || continue
  if ! [[ "$name" =~ ^[0-9]{14}_.*\.sql$ ]]; then
    echo "::error::$DIR/$name does not start with a 14-digit timestamp, so its place in the order is unknown"
    failed=1
    continue
  fi
  ts="${name%%_*}"
  if [ -z "$newest_name" ]; then
    echo "ok: $name (no migration on $BASE yet)"
  elif [[ "$ts" > "$newest_ts" ]]; then
    echo "ok: $name sorts after $newest_name on $BASE"
  else
    echo "::error::$DIR/$name sorts before, or with, $newest_name, the newest migration on $BASE. Rename it to a timestamp after $newest_ts (#727): a database that has $newest_name would apply it out of order."
    failed=1
  fi
done <<<"$added"

exit "$failed"
