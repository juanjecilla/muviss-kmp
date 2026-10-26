#!/bin/bash
# Prints the release a version follows, as "<X.Y.Z> <commit>", counting the
# versions still in flight (ADR 0030) as well as the final tags. Under staged
# releases 1.2.0 can sit in open beta, with no final tag, when the train cuts
# the next version: basing that one on the last final tag would cut 1.2.0 a
# second time, and would repeat 1.2.0's changes in the next version's notes.
#
# A final release is its tag. A version in flight is named by an RC tag
# (vX.Y.Z-rcN) or a release branch (release/X.Y.Z, local or on origin), and its
# commit is where that line left <ref>: the merge base of <ref> with its newest
# RC tag, else with its branch. `<commit>..<ref>` is then what is new since.
#
# With <before>, only versions older than it count (the notes of 1.2.0 follow
# 1.1.0 even once 1.3.0 is cut). Prints nothing when there is no earlier release;
# exits non-zero when the lookup fails, so callers must not swallow it.
#
# Usage: scripts/release/previous-release.sh [ref] [before X.Y.Z]
set -euo pipefail

REF="${1:-HEAD}"
BEFORE="${2:-}"

versions() {
  git tag -l 'v[0-9]*' | sed -E 's/^v//; s/-rc[0-9]+$//'
  git for-each-ref --format='%(refname:lstrip=-1)' refs/heads/release/ refs/remotes/origin/release/
}
counts() { # <X.Y.Z> -> true when it is older than BEFORE (or BEFORE is unset)
  [ -z "$BEFORE" ] && return 0
  [ "$1" != "$BEFORE" ] && [ "$(printf '%s\n%s\n' "$1" "$BEFORE" | sort -V | head -1)" = "$1" ]
}

prev=""
while IFS= read -r v; do
  if counts "$v"; then prev="$v"; fi
done < <(versions | grep -E '^[0-9]+\.[0-9]+\.[0-9]+$' | sort -uV)
[ -n "$prev" ] || exit 0

if git rev-parse -q --verify "refs/tags/v$prev" >/dev/null; then
  echo "$prev v$prev"
  exit 0
fi
line="$(git tag -l "v$prev-rc*" | sort -V | tail -1)"
if [ -z "$line" ]; then
  line="$(git for-each-ref --count=1 --format='%(refname)' "refs/heads/release/$prev" "refs/remotes/origin/release/$prev")"
fi
# No common ancestor (a shallow clone, say) is an error, never "no release":
# an empty base would turn the range into all of history.
base="$(git merge-base "$REF" "$line")" || { echo "no merge base between $REF and $line" >&2; exit 1; }
echo "$prev $base"
