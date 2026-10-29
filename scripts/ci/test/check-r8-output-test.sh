#!/usr/bin/env bash
# Proves check-r8-output.sh goes red (#486). Each fixture is built at run time.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
check="$here/../check-r8-output.sh"
fail=0
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT

expect() { # name want_exit dir
  "$check" "$3" >/dev/null 2>&1; got=$?
  if [ "$got" -ne "$2" ]; then echo "FAIL $1: exit $got, wanted $2"; fail=1; else echo "ok   $1"; fi
}

mkdir "$tmp/good";    echo "a.B -> c:" > "$tmp/good/mapping.txt"
mkdir "$tmp/none"
mkdir "$tmp/empty";   : > "$tmp/empty/mapping.txt"
mkdir "$tmp/missing"; echo "a.B -> c:" > "$tmp/missing/mapping.txt"; echo "-dontwarn x.Y" > "$tmp/missing/missing_rules.txt"

expect "a mapping and no missing rules passes" 0 "$tmp/good"
expect "no mapping fails"                       1 "$tmp/none"
expect "an empty mapping fails"                 1 "$tmp/empty"
expect "missing_rules.txt fails"                1 "$tmp/missing"
exit $fail
