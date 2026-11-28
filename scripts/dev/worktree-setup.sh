#!/usr/bin/env bash
# Prepares a git worktree to build: gives it the main checkout's local.properties
# (sdk.dir, TMDB / sync / Sentry keys; all gitignored, so a worktree never has
# them). Run it once from inside the worktree:
#   scripts/dev/worktree-setup.sh           copy the file (default)
#   scripts/dev/worktree-setup.sh --link    symlink it, so key changes in the main checkout reach this worktree
#   scripts/dev/worktree-setup.sh --check   report the state and change nothing
# Never overwrites a regular file, and never prints its contents.
#
# Copy or link (#866): the copy is the default because it keeps working when the
# main checkout moves or is deleted, but it goes stale when a key rotates; the
# staleness warning and --check make that visible. A link stays live but dangles
# if the main checkout moves. Its target is absolute because a worktree can sit
# anywhere `git worktree add` reaches, so a relative path would depend on where
# the worktree was made.
set -euo pipefail
# The copy holds secrets: keep it owner-only, whatever the source's mode is.
umask 077

usage() {
  cat <<'EOF'
Usage: scripts/dev/worktree-setup.sh [--link | --check]
  (no option)  copy the main checkout's local.properties into this worktree
  --link       symlink it instead, so later key changes in the main checkout reach this worktree
  --check      report the state; exit 1 unless it is set up and in sync. Changes nothing.
EOF
}

mode="copy"
if [ $# -gt 1 ]; then
  usage >&2
  exit 2
fi
case "${1:-}" in
  "") ;;
  --link) mode="link" ;;
  --check) mode="check" ;;
  -h | --help) usage; exit 0 ;;
  *) usage >&2; exit 2 ;;
esac

here="$(git rev-parse --show-toplevel)"
# The first `worktree` entry is the main checkout, whichever worktree we run from.
main="$(git worktree list --porcelain | sed -n 's/^worktree //p' | head -n 1)"

if [ "$here" = "$main" ]; then
  echo "This is the main checkout; its local.properties is already in place."
  exit 0
fi

target="$here/local.properties"
source="$main/local.properties"

# A link is ours when it resolves to the main checkout's file, whether it was
# written absolute (by this script) or relative (by hand). readlink covers a
# dangling link, which -ef cannot see.
links_to_source() {
  [ "$target" -ef "$source" ] || [ "$(readlink "$target")" = "$source" ]
}

if [ -L "$target" ]; then
  if ! links_to_source; then
    echo "local.properties here is a symlink to some other file; left unchanged. Remove it and re-run to replace it." >&2
    exit 1
  fi
  if [ ! -e "$target" ]; then
    echo "local.properties here links to the main checkout's file, which is missing. Restore that file or remove the link." >&2
    exit 1
  fi
  echo "local.properties is linked to the main checkout's file; it follows that file's keys (contents not shown)."
  exit 0
fi

if [ -e "$target" ]; then
  if [ "$mode" = link ]; then
    echo "local.properties already exists here and is not a link; --link never overwrites it. Remove it and re-run with --link." >&2
    exit 1
  fi
  if [ ! -f "$target" ]; then
    echo "local.properties here is not a regular file; left unchanged." >&2
    if [ "$mode" = check ]; then
      exit 1
    fi
    exit 0
  fi
  # A copy whose source cannot be read cannot be compared: --check must not pass it.
  if [ ! -f "$source" ] || [ ! -r "$source" ]; then
    echo "local.properties here is left unchanged; the main checkout's file is missing or unreadable, so it cannot be compared." >&2
    if [ "$mode" = check ]; then
      exit 1
    fi
    exit 0
  fi
  if ! cmp -s "$source" "$target"; then
    echo "warning: this worktree's local.properties is a copy that differs from the main checkout's, so it is stale (a key changed there?). Left unchanged (contents not shown)." >&2
    echo "To refresh it, remove it and re-run this script; --link keeps it in step from now on." >&2
    if [ "$mode" = check ]; then
      exit 1
    fi
    exit 0
  fi
  echo "local.properties here is a copy that matches the main checkout's (contents not shown)."
  exit 0
fi

if [ ! -f "$source" ]; then
  echo "No local.properties in the main checkout ($main)." >&2
  echo "Export ANDROID_HOME to your Android SDK, or create local.properties (see README.md)." >&2
  exit 1
fi

if [ "$mode" = check ]; then
  echo "local.properties is not set up in this worktree. Run scripts/dev/worktree-setup.sh (or with --link)." >&2
  exit 1
fi

if [ ! -r "$source" ]; then
  echo "The main checkout's local.properties is not readable; nothing done." >&2
  exit 1
fi

# Publishing: `ln` refuses to replace an existing file or link. But when the
# destination is a directory, `ln` creates an entry inside it and succeeds. So after
# every `ln` the destination is checked to be the entry we meant to create; if not,
# the one stray entry this call made is removed and the call fails.

if [ "$mode" = link ]; then
  if ln -s "$source" "$target" 2>/dev/null; then
    if [ -L "$target" ]; then
      echo "Linked this worktree's local.properties to the main checkout's file (contents not shown)."
      exit 0
    fi
    # A directory appeared at the destination: ln linked inside it. Remove that entry.
    rm -f "$target/$(basename "$source")"
    echo "A directory appeared at the destination meanwhile; nothing linked." >&2
    exit 1
  fi
  if [ -e "$target" ] || [ -L "$target" ]; then
    echo "local.properties appeared in this worktree meanwhile; --link did not replace it." >&2
    exit 1
  fi
  echo "Could not link local.properties into this worktree." >&2
  exit 1
fi

# The copy is prepared under a temp name beside the target, so it can be published
# with a hard link: the target shows up complete or not at all, and a partial copy
# never reaches the target name.
tmp=""
cleanup() {
  if [ -n "$tmp" ]; then
    rm -f "$tmp"
  fi
}
trap cleanup EXIT

# A run killed by SIGKILL skips the trap and leaves its temp file behind (#1021).
# Temp names carry a script-owned `tmp.` infix, so only those are removed, and only
# regular files: a user's own `.local.properties.backup` is never matched.
find "$here" -maxdepth 1 -name '.local.properties.tmp.??????' -type f -exec rm -f {} +

# mktemp creates the file 0600 whatever the umask.
tmp="$(mktemp "$here/.local.properties.tmp.XXXXXX")"
cp "$source" "$tmp"
chmod 600 "$tmp"

if ln "$tmp" "$target" 2>/dev/null; then
  if [ "$target" -ef "$tmp" ]; then
    rm -f "$tmp"
    tmp=""
    echo "Copied local.properties from the main checkout into this worktree (contents not shown)."
    exit 0
  fi
  # A directory appeared at the destination: ln linked the temp file inside it.
  rm -f "$target/$(basename "$tmp")"
  echo "A directory appeared at the destination meanwhile; nothing copied." >&2
  exit 1
fi

# ln failed: either something appeared at the target after the check above (leave it
# alone), or the copy could not be published at all.
if [ -e "$target" ] || [ -L "$target" ]; then
  echo "local.properties appeared in this worktree meanwhile; left unchanged."
  exit 0
fi
echo "Could not publish local.properties into this worktree." >&2
exit 1
