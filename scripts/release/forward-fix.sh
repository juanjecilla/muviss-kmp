#!/bin/bash
# Forward propagation of a fix (ADR 0029). A PR merged into release/X.Y.Z or
# hotfix/X.Y.Z is opened again, as a PR, into every newer open release/* branch
# and into develop, each linking the original, so a fix made on the oldest line
# is not lost from the next release. The PRs are opened by the release App
# (forward-fix.yml), so their checks run.
#
# A forward PR whose cherry-pick conflicts is still opened: empty, with the
# conflicting commit named in its body, and it stays open for a person. A
# forward PR into a release/* branch is a fix in flight, so it holds that
# version's promotion (release-promote.yml) until it merges.
#
#   forward-fix.sh targets SOURCE_BRANCH                       newer open release/* branches, then develop
#   forward-fix.sh forward PR SOURCE_BRANCH MERGE_SHA TARGET   open the forward PR into TARGET
#   forward-fix.sh all PR SOURCE_BRANCH MERGE_SHA              every target, one PR each
#
# Needs GH_REPO and GH_TOKEN for gh. Git identity comes from the caller.
set -euo pipefail
cd "$(dirname "$0")/../.." || exit 1

# The newer open release/* branches for a source branch, then develop. "Newer"
# is by version (sort -V), so a hotfix/1.0.1 forwards to release/1.1.0 but not
# to release/1.0.0.
targets() {
  local source="$1" version open v
  version="${source#*/}"
  open="$(git ls-remote --heads origin 'release/*' | sed -n 's#.*refs/heads/release/##p')"
  for v in $open; do
    [ "$v" = "$version" ] && continue
    if [ "$(printf '%s\n%s\n' "$version" "$v" | sort -V | tail -1)" = "$v" ]; then
      echo "release/$v"
    fi
  done | sort -V
  echo develop
}

# The cherry-pick of a merge commit needs its first parent as the base.
pick_args() {
  local sha="$1" parents
  parents="$(git rev-list --parents -n 1 "$sha" | wc -w)"
  if [ "$parents" -gt 2 ]; then echo "-m 1"; fi
}

forward() {
  local pr="$1" source="$2" sha="$3" target="$4"
  local branch="forward/pr-$pr-to-${target//\//-}"
  local existing body title status="clean" flags

  existing="$(gh pr list --repo "$GH_REPO" --head "$branch" --state open --json number --jq '.[0].number // ""')"
  if [ -n "$existing" ]; then
    echo "forward #$pr to $target: already open as #$existing"
    return 0
  fi

  git fetch --quiet origin "$target"
  git switch -q -C "$branch" "origin/$target"
  # shellcheck disable=SC2046
  flags="$(pick_args "$sha")"
  # shellcheck disable=SC2086
  if git cherry-pick $flags "$sha" >/dev/null 2>&1; then
    :
  else
    git cherry-pick --abort >/dev/null 2>&1 || true
    git commit -q --allow-empty -m "chore(release): forward #$pr to $target needs a person" \
      -m "The cherry-pick of $sha does not apply cleanly to $target. Resolve it on this branch."
    status="conflict"
  fi
  git push -q --force origin "HEAD:refs/heads/$branch"

  title="chore(release): forward #$pr to $target"
  body="Forwards #$pr, merged into \`$source\`, so \`$target\` gets the same fix (ADR 0029). The original is #$pr."
  if [ "$status" = "conflict" ]; then
    body="$body

**Conflicts.** The cherry-pick of \`$sha\` does not apply cleanly to \`$target\`, so this PR is empty on purpose. Resolve it on \`$branch\` (cherry-pick \`$sha\`, fix the conflicts, push) and merge it. Until it merges, a forward PR into a release branch holds that version's promotion."
  fi
  gh pr create --repo "$GH_REPO" --base "$target" --head "$branch" --title "$title" --label no-landing --body "$body" >/dev/null
  echo "forward #$pr to $target: opened ($status)"
}

case "${1:-}" in
  targets)
    targets "${2:?usage: $0 targets SOURCE_BRANCH}"
    ;;
  forward)
    forward "${2:?}" "${3:?}" "${4:?}" "${5:?}"
    ;;
  all)
    pr="${2:?usage: $0 all PR SOURCE_BRANCH MERGE_SHA}"
    source="${3:?}"
    sha="${4:?}"
    targets "$source" | while read -r target; do
      forward "$pr" "$source" "$sha" "$target"
    done
    ;;
  *)
    echo "usage: $0 targets SOURCE | forward PR SOURCE SHA TARGET | all PR SOURCE SHA" >&2
    exit 2
    ;;
esac
