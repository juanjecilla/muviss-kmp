#!/bin/bash
# Puts a PR into release/X.Y.Z or hotfix/X.Y.Z under the milestone X.Y.Z (ADR
# 0030), so work is pinned to the version it ships in. The milestone is created
# when the branch was cut by hand (the train creates it otherwise). A base that
# is not a release or hotfix branch is left alone.
#
#   milestone.sh PR BASE_BRANCH
#
# Needs GH_REPO and GH_TOKEN for gh.
set -euo pipefail

PR="${1:?usage: $0 PR BASE_BRANCH}"
BASE="${2:?usage: $0 PR BASE_BRANCH}"
if [[ ! "$BASE" =~ ^(release|hotfix)/([0-9]+\.[0-9]+\.[0-9]+)$ ]]; then
  echo "not a release branch ($BASE); no milestone"
  exit 0
fi
VERSION="${BASH_REMATCH[2]}"

# Exact title match: "1.1.0" must not find "1.1.01".
find_milestone() {
  local found
  found="$(gh api --paginate "repos/$GH_REPO/milestones?state=all&per_page=100" \
    --jq ".[] | select(.title == \"$VERSION\") | .number")"
  echo "${found%%$'\n'*}"
}

NUMBER="$(find_milestone)"
if [ -z "$NUMBER" ]; then
  # Two PRs opened at once into a new branch can both get here; the loser's
  # POST is a 422 (already_exists), so look again rather than fail.
  if gh api -X POST "repos/$GH_REPO/milestones" -f title="$VERSION" >/dev/null 2>&1; then
    echo "created milestone $VERSION"
  elif [ -z "$(find_milestone)" ]; then
    echo "could not create milestone $VERSION" >&2
    exit 1
  fi
fi

gh pr edit "$PR" --repo "$GH_REPO" --milestone "$VERSION" >/dev/null
echo "#$PR: milestone $VERSION"
