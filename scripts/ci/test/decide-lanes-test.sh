#!/usr/bin/env bash
# Proves scripts/ci/decide-lanes.sh fails open and filters correctly (#490). Every fixture is a
# throwaway git repository built here, so nothing is committed to rot.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
script="$here/../decide-lanes.sh"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
fail=0

repo() { # builds $tmp/r with a base commit on branch "base" and a head commit changing the given files
  rm -rf "$tmp/r" "$tmp/origin"
  git init -q "$tmp/origin" -b base
  (cd "$tmp/origin" && git -c user.email=t@t -c user.name=t commit -q --allow-empty -m base)
  git clone -q "$tmp/origin" "$tmp/r"
  (cd "$tmp/r" && git checkout -q -b change)
  for f in "$@"; do
    mkdir -p "$tmp/r/$(dirname "$f")"; echo "x" > "$tmp/r/$f"
  done
  (cd "$tmp/r" && git add -A && git -c user.email=t@t -c user.name=t commit -q -m change --allow-empty)
}

lanes() { # EVENT BASE_REF -> "code ios supabase"
  local out="$tmp/out"; : > "$out"
  (cd "$tmp/r" && EVENT="$1" BASE_REF="$2" GITHUB_OUTPUT="$out" "$script" >/dev/null 2>&1)
  printf '%s %s %s' "$(grep '^code=' "$out" | tail -1 | cut -d= -f2)" "$(grep '^ios=' "$out" | tail -1 | cut -d= -f2)" "$(grep '^supabase=' "$out" | tail -1 | cut -d= -f2)"
}

expect() { # name want got
  if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: wanted '$2', got '$3'"; fail=1; fi
}

repo README.md
expect "a push runs every lane" "true true true" "$(lanes push base)"
expect "a pull request whose base cannot be diffed runs every lane" "true true true" "$(lanes pull_request no-such-branch)"

repo
expect "an empty diff runs every lane" "true true true" "$(lanes pull_request base)"

repo docs/NOTES.md README.md
expect "a docs-only change runs no lane" "false false false" "$(lanes pull_request base)"

repo feature/search/ui/Foo.kt
expect "a feature change reaches the iOS link and the code lanes" "true true false" "$(lanes pull_request base)"

repo app/androidApp/src/Main.kt
expect "an Android-only change skips iOS" "true false false" "$(lanes pull_request base)"

repo supabase/migrations/1.sql
expect "a server change runs the supabase lane" "true false true" "$(lanes pull_request base)"

repo .github/workflows/ci.yml
expect "a change to the CI file runs the CI it edits" "true true true" "$(lanes pull_request base)"

repo scripts/ci/decide-lanes.sh
expect "a change to the lane filter runs every lane" "true true true" "$(lanes pull_request base)"
# More than a pipe buffer of paths, with the only iOS match first: `echo | grep -q` used to stop
# reading at that match, the echo died of SIGPIPE, and pipefail turned the lane off.
big=(app/shared/Early.kt)
for i in $(seq 1 3000); do big+=("zz/a-long-directory-name-to-fill-the-pipe/file-$i.md"); done
repo "${big[@]}"
expect "a large diff with an early match keeps its lanes" "true true false" "$(lanes pull_request base)"

repo README.md
(cd "$tmp/r" && EVENT=push BASE_REF=base GITHUB_OUTPUT="$tmp/no-such-dir/out" "$script" >/dev/null 2>&1)
expect "an output that cannot be written fails the job" "1" "$?"
(cd "$tmp/r" && EVENT=pull_request BASE_REF=base GITHUB_OUTPUT="$tmp/no-such-dir/out" "$script" >/dev/null 2>&1)
expect "a lane decision that cannot be written fails the job" "1" "$?"
exit $fail
