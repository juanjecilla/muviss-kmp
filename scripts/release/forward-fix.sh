#!/bin/bash
# Forward propagation of a fix (ADR 0030). A PR merged into release/X.Y.Z or
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

# Run from a copy: forward() switches the worktree to each target branch, and
# bash reads a script as it goes, so the file on disk must not change under it
# (an older target has an older forward-fix.sh, or none).
if [ -z "${FORWARD_FIX_RUNNING_COPY:-}" ]; then
  copy="$(mktemp)"
  cp "$0" "$copy"
  FORWARD_FIX_RUNNING_COPY="$copy" FORWARD_FIX_REPO="$PWD" exec bash "$copy" "$@"
fi
cd "$FORWARD_FIX_REPO" || exit 1
trap 'rm -f "$FORWARD_FIX_RUNNING_COPY"' EXIT

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

# The release notes of one version belong to that version's release line. On a
# line of another version their directory is stray (a notes edit to v1.5.0 must
# not leave a v1.5.0/ directory on release/1.6.0), so a forward to such a line
# drops them. develop takes every version. Each such path is put back to the
# target's state (removed when the target does not have it), whether the pick
# applied cleanly or conflicted on it. Logs to stderr, and records the skipped
# versions in SKIPPED_NOTES (space separated) for the caller.
SKIPPED_NOTES=""
skip_other_version_notes() {
  local target="$1" sha="$2" path version want
  want="${target#release/}"
  [ "$target" = develop ] && return 0
  while read -r path; do
    [ -n "$path" ] || continue
    version="${path#fastlane/release-notes/v}"
    version="${version%%/*}"
    [ "$version" = "$want" ] && continue
    if git cat-file -e "origin/$target:$path" 2>/dev/null; then
      git checkout -q "origin/$target" -- "$path" || return 1
      git add -- "$path" || return 1
    else
      git rm -q -f --ignore-unmatch -- "$path" || return 1
    fi
    if [[ " $SKIPPED_NOTES " != *" $version "* ]]; then
      echo "skipped v$version notes for $target" >&2
      SKIPPED_NOTES="$SKIPPED_NOTES $version"
    fi
  done < <(git diff --name-only "$sha^1" "$sha" | grep -E '^fastlane/release-notes/v[^/]+/' || true)
  return 0
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
  local existing body title status="clean" flags skipped_line=""

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
    # A notes file added by a clean pick (a new locale, say) must not reach a
    # line of another version either: amend it away.
    skip_other_version_notes "$target" "$sha"
    if [ -n "$SKIPPED_NOTES" ] && { ! git diff --cached --quiet || ! git diff --quiet; }; then
      git commit -q --amend --no-edit --allow-empty
    fi
  elif skip_other_version_notes "$target" "$sha" && [ -z "$(git ls-files -u)" ] \
    && ! git diff --cached --quiet && GIT_EDITOR=true git cherry-pick --continue >/dev/null 2>&1; then
    # The only conflicts were release notes, now settled above: the pick went on.
    :
  elif [ -z "$(git ls-files -u)" ] && git diff --cached --quiet && git diff --quiet; then
    # Nothing conflicts and nothing changed: the target already has the fix
    # (it also landed there some other way), or the only change was another
    # version's release notes. A PR would be empty, and on a release branch it
    # would hold the promotion for nothing.
    git cherry-pick --abort >/dev/null 2>&1 || git cherry-pick --skip >/dev/null 2>&1 || true
    if [ -n "$SKIPPED_NOTES" ]; then
      echo "forward #$pr to $target: nothing left once the other version's release notes are skipped"
    else
      echo "forward #$pr to $target: already there, skipped"
    fi
    return 0
  else
    git cherry-pick --abort >/dev/null 2>&1 || true
    git commit -q --allow-empty -m "chore(release): forward #$pr to $target needs a person" \
      -m "The cherry-pick of $sha does not apply cleanly to $target. Resolve it on this branch."
    status="conflict"
  fi
  if [ "$status" = clean ] && git diff --quiet "origin/$target" HEAD; then
    if [ -n "$SKIPPED_NOTES" ]; then
      echo "forward #$pr to $target: nothing left once the other version's release notes are skipped"
    else
      echo "forward #$pr to $target: already there, skipped"
    fi
    return 0
  fi
  git push -q --force origin "HEAD:refs/heads/$branch"

  title="chore(release): forward #$pr to $target"
  body="Forwards #$pr, merged into \`$source\`, so \`$target\` gets the same fix (ADR 0030). The original is #$pr."
  if [ -n "$SKIPPED_NOTES" ]; then
    skipped_line="$(for v in $SKIPPED_NOTES; do printf 'v%s, ' "$v"; done)"
    body="$body

**Release notes.** Left out: the notes of ${skipped_line%, }, which belong to their own release line, not to \`$target\`."
  fi
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
