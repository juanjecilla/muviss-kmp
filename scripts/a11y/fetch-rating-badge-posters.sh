#!/usr/bin/env bash
# Fetch TMDB poster images as local fixtures for the RatingBadge contrast test (#592).
#
# Posters are not committed (licence and size). This writes them to a folder
# OUTSIDE the repo, which the opt-in test reads through MUVISS_POSTER_DIR.
#
# usage:
#   TMDB_READ_TOKEN=... scripts/a11y/fetch-rating-badge-posters.sh <dest-dir> [count]
#   TMDB_API_KEY=...    scripts/a11y/fetch-rating-badge-posters.sh <dest-dir> [count]
#
# The v4 read token (TMDB_READ_TOKEN) is preferred: it goes in an Authorization
# header, not the URL. The v3 key (TMDB_API_KEY) is the fallback, as in the app.
# Neither value is printed. Files are named <tmdb-id>.jpg, so the test's table
# can be traced back to /movie/<id> on themoviedb.org.
#
# Source: /3/movie/popular, page 1, then image.tmdb.org w500 posters. The first
# <count> titles with a poster are kept (default 12). This is an arbitrary,
# reproducible sample, not a lightest/darkest selection: the test measures the
# extremes, and the table shows which fixtures are the worst.

set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "usage: $0 <dest-dir> [count]" >&2
  exit 2
fi

dest="$1"
count="${2:-12}"

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

curl -fsS -G "https://api.themoviedb.org/3/movie/popular" \
  -d language=en-US -d page=1 ${auth_args[@]+"${auth_args[@]}"} ${key_args[@]+"${key_args[@]}"} > "$listing"

jq -r --argjson n "$count" \
  '[.results[] | select(.poster_path != null)] | .[:$n][] | "\(.id) \(.poster_path)"' \
  "$listing" |
while read -r id poster_path; do
  curl -fsS "https://image.tmdb.org/t/p/w500${poster_path}" -o "$dest/${id}.jpg"
  echo "fetched ${id}.jpg"
done
