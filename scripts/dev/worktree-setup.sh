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

# Any entry already there is left alone: a regular file, a directory, or a symlink
# even a dangling one (-e does not see that, and cp would follow it).
if [ -e "$target" ] || [ -L "$target" ]; then
  echo "local.properties already exists in this worktree; left unchanged."
  exit 0
fi

if [ ! -f "$source" ]; then
  echo "No local.properties in the main checkout ($main)." >&2
  echo "Export ANDROID_HOME to your Android SDK, or create local.properties (see README.md)." >&2
  exit 1
fi

if [ ! -r "$source" ]; then
  echo "The main checkout's local.properties is not readable; nothing copied." >&2
  exit 1
fi

# The copy is prepared under a temp name beside the target, so it can be published
# with a hard link: `ln` refuses to replace an existing entry, and the target shows
# up complete or not at all. A partial copy never reaches the target name.
tmp=""
cleanup() {
  if [ -n "$tmp" ]; then
    rm -f "$tmp"
  fi
}
trap cleanup EXIT

# mktemp creates the file 0600 whatever the umask.
tmp="$(mktemp "$here/.local.properties.XXXXXX")"
cp "$source" "$tmp"
chmod 600 "$tmp"

if ln "$tmp" "$target" 2>/dev/null; then
  rm -f "$tmp"
  tmp=""
  echo "Copied local.properties from the main checkout into this worktree (contents not shown)."
  exit 0
fi

# ln failed: either something appeared at the target after the check above (leave it
# alone), or the copy could not be published at all.
if [ -e "$target" ] || [ -L "$target" ]; then
  echo "local.properties appeared in this worktree meanwhile; left unchanged."
  exit 0
fi
echo "Could not publish local.properties into this worktree." >&2
exit 1
