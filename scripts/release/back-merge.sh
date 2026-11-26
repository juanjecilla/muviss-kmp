#!/bin/bash
# The automatic back-merge (ADR 0030, amendment of 2026-11-16; #890).
# release.yml opens `back-merge/vX.Y.Z → develop` as the release App after every
# release. CodeRabbit skips App-authored PRs, so nothing ever approved it.
# .github/workflows/back-merge-auto.yml approves and merges it, and only when
# this script says `merge`. GitHub calls go through `gh`, which reads GH_REPO.
#
#   back-merge.sh decide            one line: `merge`, `hold: <why>` or `ignore: <why>`
#                                   env: HEAD_REF BASE_REF AUTHOR SAME_REPO HEAD_SHA
#                                        TESTED_SHA APP_LOGIN (as gh prints it: app/<slug>)
#   back-merge.sh notify X.Y.Z KEY MESSAGE
#                                   comment on "Release X.Y.Z", once per KEY
#   back-merge.sh stale             notify for every back-merge PR open over a day
#                                   env: NOW (ISO 8601, defaults to the clock)
#   back-merge.sh pr-at SHA         the open back-merge PR into develop whose head is
#                                   SHA, as "NUMBER BRANCH"; prints nothing when there
#                                   is none. A push to main completes CI on the
#                                   release merge commit, which names no PR (#1030).
#
# `decide` only merges a back-merge nobody touched: the PR head is still the
# commit the release tag is on. A conflict resolved on the branch is code no one
# reviewed, so it waits for a person.
set -euo pipefail

decide() {
  [ "${BASE_REF:-}" = develop ] || { echo "ignore: not a PR into develop"; return; }
  case "${HEAD_REF:-}" in
    back-merge/v[0-9]*) ;;
    *) echo "ignore: not a back-merge branch"; return ;;
  esac
  [ "${SAME_REPO:-}" = true ] || { echo "ignore: the branch is in a fork"; return; }
  [ -n "${APP_LOGIN:-}" ] && [ "${AUTHOR:-}" = "$APP_LOGIN" ] \
    || { echo "ignore: not opened by the release App"; return; }

  local tag="${HEAD_REF#back-merge/}" tagged
  tagged="$(git rev-parse --verify -q "refs/tags/$tag^{commit}")" \
    || { echo "hold: the tag $tag does not exist"; return; }
  [ "${HEAD_SHA:-}" = "$tagged" ] \
    || { echo "hold: the branch moved past $tag (a hand resolution needs a person's review)"; return; }
  [ "${TESTED_SHA:-}" = "$tagged" ] \
    || { echo "hold: CI ran on another commit than $tag"; return; }
  echo merge
}

# A failed read or write returns nonzero and posts nothing: a read that failed
# must not look like "no comment yet" and post a second notice.
notify() {
  local version="$1" key="$2" message="$3" issue comments marker
  marker="<!-- back-merge:$key -->"
  issue="$(gh issue list --label release --state all --search "\"Release $version\" in:title" \
    --json number,title --jq ".[] | select(.title == \"Release $version\") | .number" | sed -n 1p)" \
    || { echo "could not look up \"Release $version\"" >&2; return 1; }
  [ -n "$issue" ] || { echo "no \"Release $version\" issue; nothing to comment on" >&2; return 0; }
  comments="$(gh issue view "$issue" --json comments --jq '.comments[].body')" \
    || { echo "could not read the comments of \"Release $version\"" >&2; return 1; }
  if grep -qF -- "$marker" <<<"$comments"; then
    return 0
  fi
  gh issue comment "$issue" --body "$(printf '%s\n\n%s' "$marker" "$message")" >/dev/null
}

# One notice per stale PR. A PR whose notice fails does not stop the others;
# the run fails at the end, so a rerun sees it.
stale() {
  local prs due version number failed=0
  # gh api pages through every open PR into develop; a plain gh pr list is
  # capped by its --limit.
  prs="$(gh api --paginate "repos/{owner}/{repo}/pulls?base=develop&state=open&per_page=100" \
    --jq '.[] | {number, headRefName: .head.ref, createdAt: .created_at, isCrossRepository: (.head.repo.full_name != .base.repo.full_name)}')"
  due="$(PRS="$prs" NOW="${NOW:-}" ruby -rjson -rtime -e '
    now = ENV["NOW"].to_s.empty? ? Time.now.utc : Time.parse(ENV["NOW"])
    ENV["PRS"].each_line do |line|
      next if line.strip.empty?
      pr = JSON.parse(line)
      next if pr["isCrossRepository"]
      version = pr["headRefName"][%r{\Aback-merge/v(\d+\.\d+\.\d+)\z}, 1] or next
      next if now - Time.parse(pr["createdAt"]) < 24 * 3600
      puts "#{version} #{pr["number"]}"
    end
  ')"
  while read -r version number; do
    [ -n "$version" ] || continue
    notify "$version" "stale-$number" \
      "Back-merge #$number (\`v$version\` into \`develop\`) has been open for more than a day. It did not merge by itself: see the PR for why, and merge it with a merge commit (docs/RELEASING.md)." \
      || { echo "could not notify about back-merge #$number" >&2; failed=1; }
  done <<<"$due"
  return "$failed"
}

# A failed listing exits nonzero: a run that cannot see the PR must not read as
# "nothing to merge" and stop for good.
pr_at() {
  local sha="$1" prs
  [ -n "$sha" ] || { echo "usage: $0 pr-at SHA" >&2; return 2; }
  prs="$(gh pr list --base develop --state open --limit 200 --json number,headRefName,headRefOid)" \
    || { echo "could not list the open PRs into develop" >&2; return 1; }
  jq -r --arg sha "$sha" \
    '[.[] | select(.headRefOid == $sha and (.headRefName | test("^back-merge/v[0-9]")))] | .[0] // empty | "\(.number) \(.headRefName)"' <<<"$prs"
}

case "${1:-}" in
  decide) decide ;;
  pr-at) pr_at "${2:-}" ;;
  notify) [ $# -eq 4 ] || { echo "usage: $0 notify X.Y.Z KEY MESSAGE" >&2; exit 2; }; notify "$2" "$3" "$4" ;;
  stale) stale ;;
  *) echo "usage: $0 decide | notify X.Y.Z KEY MESSAGE | stale | pr-at SHA" >&2; exit 2 ;;
esac
