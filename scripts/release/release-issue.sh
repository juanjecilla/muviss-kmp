#!/bin/bash
# The per-version milestone and "Release X.Y.Z" issue (ADR 0030), used by
# .github/workflows/release-train.yml and release-promote.yml. The issue's
# marker is the version's state; the schedule and the arithmetic live in
# fastlane/release_stages.rb. GitHub calls go through `gh`, which reads GH_REPO.
#
#   release-issue.sh labels              create the release and release-blocker labels
#   release-issue.sh plan                the train's stagger decision, as JSON
#   release-issue.sh cut-date X.Y.Z      the first commit date on release/X.Y.Z past develop
#   release-issue.sh ensure X.Y.Z [CUT]  milestone + issue, once (CUT defaults to today, UTC)
set -euo pipefail
cd "$(dirname "$0")/../.." || exit 1

STAGES=fastlane/release_stages.rb

labels() {
  gh label create release --color 0E8A16 --description "A staged release's tracking issue (ADR 0030)" --force >/dev/null
  gh label create release-blocker --color B60205 --description "Holds the next staged release step (ADR 0030)" --force >/dev/null
}

plan() {
  local open issues input
  open="$(git ls-remote --heads origin 'release/*' | sed -n 's#.*refs/heads/release/##p')"
  issues="$(gh issue list --label release --state all --limit 500 --json title,body)"
  input="$(mktemp)"
  OPEN="$open" ISSUES="$issues" ruby -rjson -e '
    open = ENV["OPEN"].to_s.split("\n").reject(&:empty?)
    puts JSON.generate("open" => open, "issues" => JSON.parse(ENV["ISSUES"]))
  ' >"$input"
  ruby "$STAGES" plan "$input"
  rm -f "$input"
}

cut_date() {
  local date
  date="$(git log --reverse --format=%cs "origin/develop..origin/release/$1" | sed -n 1p)"
  echo "${date:-$(date -u +%F)}"
}

ensure_milestone() {
  local version="$1" number
  number="$(gh api --paginate "repos/$GH_REPO/milestones?state=all&per_page=100" --jq ".[] | select(.title == \"$version\") | .number")"
  # One line at most: a milestone title is unique, and "$(...)" keeps the first line only.
  number="${number%%$'\n'*}"
  if [ -z "$number" ]; then
    gh api -X POST "repos/$GH_REPO/milestones" -f title="$version" >/dev/null
    echo "created milestone $version"
  fi
}

ensure() {
  local version="$1" cut="${2:-$(date -u +%F)}" title body existing
  title="Release $version"
  existing="$(gh issue list --label release --state all --limit 500 --json title --jq '.[].title')"
  if printf '%s\n' "$existing" | grep -qxF "$title"; then
    echo "$title exists"
    return 0
  fi
  ensure_milestone "$version"
  body="$(mktemp)"
  ruby "$STAGES" render "$version" "$cut" >"$body"
  gh issue create --title "$title" --label release --milestone "$version" --body-file "$body" >/dev/null
  rm -f "$body"
  echo "created $title (cut $cut)"
}

case "${1:-}" in
  labels) labels ;;
  plan) plan ;;
  cut-date) cut_date "${2:?usage: $0 cut-date X.Y.Z}" ;;
  ensure) ensure "${2:?usage: $0 ensure X.Y.Z [CUT]}" "${3:-}" ;;
  *) echo "usage: $0 labels | plan | cut-date X.Y.Z | ensure X.Y.Z [CUT]" >&2; exit 2 ;;
esac
