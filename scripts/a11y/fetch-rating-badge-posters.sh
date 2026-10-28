#!/usr/bin/env bash
# Fetch TMDB poster images as local fixtures for the RatingBadge contrast test (#592).
#
# Posters are not committed (licence and size). This writes them to a folder
# OUTSIDE the repo, which the opt-in test reads through MUVISS_POSTER_DIR.
#
# usage:
#   TMDB_READ_TOKEN=... scripts/a11y/fetch-rating-badge-posters.sh <dest-dir> [count] [popular|top_rated]
#   TMDB_API_KEY=...    scripts/a11y/fetch-rating-badge-posters.sh <dest-dir> [count] [popular|top_rated]
#
# The v4 read token (TMDB_READ_TOKEN) is preferred: it goes in an Authorization
# header, not the URL. The v3 key (TMDB_API_KEY) is the fallback, as in the app.
# Neither value is printed. Files are named <tmdb-id>.jpg, so the test's table
# can be traced back to /movie/<id> on themoviedb.org. Files already present are
# kept, so running it again with a larger count only adds posters.
#
# Source: /3/movie/<list>, pages 1 upward, until <count> titles with a poster
# have been fetched (default 12), then image.tmdb.org w500 posters. The list is
# a reproducible sample, not a lightest/darkest selection: the test ranks the
# posters, and docs/ACCESSIBILITY.md records which ones were picked from the ranking.

set -euo pipefail

if [[ $# -lt 1 || $# -gt 3 ]]; then
  echo "usage: $0 <dest-dir> [count] [popular|top_rated]" >&2
  exit 2
fi

dest="$1"
count="${2:-12}"
list="${3:-popular}"

case "$list" in
  popular|top_rated) ;;
  *) echo "list must be popular or top_rated" >&2; exit 2 ;;
esac

if [[ -n "${TMDB_READ_TOKEN:-}" ]]; then
  auth_args=(-H "Authorization: Bearer ${TMDB_READ_TOKEN}")
  key_args=()
elif [[ -n "${TMDB_API_KEY:-}" ]]; then
  auth_args=()
  key_args=(--data-urlencode "api_key=${TMDB_API_KEY}")
else
  echo "set TMDB_READ_TOKEN or TMDB_API_KEY" >&2
  exit 2
fi

case "$dest" in
  "$(git rev-parse --show-toplevel 2>/dev/null || echo /nonexistent)"*)
    echo "refusing to write fixtures inside the repo: $dest" >&2
    exit 2
    ;;
esac

for tool in curl jq; do
  command -v "$tool" >/dev/null || { echo "$tool is required" >&2; exit 2; }
done

mkdir -p "$dest"
listing="$(mktemp)"
trap 'rm -f "$listing"' EXIT

fetched=0
page=1
while (( fetched < count && page <= 20 )); do
  curl -fsS -G "https://api.themoviedb.org/3/movie/${list}" \
    -d language=en-US -d "page=${page}" ${auth_args[@]+"${auth_args[@]}"} ${key_args[@]+"${key_args[@]}"} > "$listing"

  while read -r id poster_path; do
    (( fetched < count )) || break
    target="$dest/${id}.jpg"
    if [[ ! -s "$target" ]]; then
      curl -fsS "https://image.tmdb.org/t/p/w500${poster_path}" -o "$target"
      echo "fetched ${id}.jpg (${list} p${page})"
    fi
    fetched=$((fetched + 1))
  done < <(jq -r '.results[] | select(.poster_path != null) | "\(.id) \(.poster_path)"' "$listing")

  page=$((page + 1))
done

echo "collected ${fetched} posters in ${dest}"
