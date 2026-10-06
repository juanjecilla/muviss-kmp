#!/bin/bash
# Prints the version the release train should cut from <ref> (default
# origin/develop), from the conventional commits since the last final tag
# (ADR 0025): a breaking change (`type!:` or a `BREAKING CHANGE` footer) bumps
# major, any `feat` bumps minor, anything else bumps patch.
#
# Exit 0 with the version on stdout; exit 3 when there is nothing to release.
# Merge commits are ignored: the back-merge from main after every release is
# one, and it must not count as a change.
#
# Usage: scripts/release/next-version.sh [ref]
set -euo pipefail

REF="${1:-origin/develop}"

# The newest final tag anywhere, not the nearest one reachable from REF: until
# the back-merge PR lands, develop cannot reach the tag that was just put on
# main. `PREV..REF` still excludes everything that release shipped.
PREV="$(git tag -l 'v[0-9]*' | grep -v -- '-' | sort -V | tail -1 || true)"
if [ -z "$PREV" ]; then
  echo "1.0.0"
  exit 0
fi

range="$PREV..$REF"
subjects="$(git log --no-merges --format='%s' "$range")"
if [ -z "$subjects" ]; then
  echo "nothing to release on $REF since $PREV" >&2
  exit 3
fi
bodies="$(git log --no-merges --format='%b' "$range")"

IFS=. read -r major minor patch <<<"${PREV#v}"
if grep -qE '^[a-z]+(\([^)]*\))?!:' <<<"$subjects" || grep -qE '^BREAKING[ -]CHANGE:' <<<"$bodies"; then
  echo "$((major + 1)).0.0"
elif grep -qE '^feat(\([^)]*\))?:' <<<"$subjects"; then
  echo "$major.$((minor + 1)).0"
else
  echo "$major.$minor.$((patch + 1))"
fi
