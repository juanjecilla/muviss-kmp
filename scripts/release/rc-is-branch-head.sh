#!/bin/bash
# Did the release branch move after its last release candidate? release.yml's
# tag job asks before it tags: a merge that carries commits newer than the RC
# ships code no tester ran.
#
# Exit 0 when <rc-tag> is the head of origin/<head-ref>; exit 1 when it is not.
# When the branch is already gone (GitHub deletes a merged branch before the
# job runs), the merge commit's second parent is what the branch head was.
#
# Usage: scripts/release/rc-is-branch-head.sh <rc-tag> <head-ref> <merge-sha>
set -euo pipefail

RC_TAG="$1"
HEAD_REF="$2"
MERGE_SHA="$3"

rc="$(git rev-parse "$RC_TAG^{commit}")"
# --verify -q: without it, rev-parse prints a name it cannot resolve back on
# stdout before failing, and the fallback's commit is appended to that (#891).
head="$(git rev-parse --verify -q "origin/$HEAD_REF^{commit}" || git rev-parse --verify "$MERGE_SHA^2^{commit}")"
[ "$rc" = "$head" ]
