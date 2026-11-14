#!/usr/bin/env bash
# Prepares a git worktree to build: copies the main checkout's local.properties
# (sdk.dir, TMDB / sync / Sentry keys; all gitignored, so a worktree never has
# them) into this worktree. Run it once from inside the worktree:
#   scripts/dev/worktree-setup.sh
# Never overwrites an existing local.properties, and never prints its contents.
set -euo pipefail
# The copy holds secrets: keep it owner-only, whatever the source's mode is.
umask 077

here="$(git rev-parse --show-toplevel)"
# The first `worktree` entry is the main checkout, whichever worktree we run from.
main="$(git worktree list --porcelain | sed -n 's/^worktree //p' | head -n 1)"

if [ "$here" = "$main" ]; then
  echo "This is the main checkout; its local.properties is already in place."
  exit 0
fi

target="$here/local.properties"
source="$main/local.properties"

if [ -e "$target" ]; then
  echo "local.properties already exists in this worktree; left unchanged."
  exit 0
fi

if [ ! -f "$source" ]; then
  echo "No local.properties in the main checkout ($main)." >&2
  echo "Export ANDROID_HOME to your Android SDK, or create local.properties (see README.md)." >&2
  exit 1
fi

cp "$source" "$target"
echo "Copied local.properties from the main checkout into this worktree (contents not shown)."
