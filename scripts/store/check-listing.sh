#!/bin/bash
# Checks the Play listing under fastlane/metadata/android/ against Play's field
# limits, so an over-long description fails here rather than at upload time
# after a full signed build. Used by start-release.sh and the Fastfile.
set -euo pipefail
cd "$(dirname "$0")/../.." || exit 1

limit() {
  case "$1" in
    title.txt) echo 30 ;;
    short_description.txt) echo 80 ;;
    full_description.txt) echo 4000 ;;
    *) echo "" ;;
  esac
}

status=0
for dir in fastlane/metadata/android/*/; do
  locale="$(basename "$dir")"
  for field in title.txt short_description.txt full_description.txt; do
    file="$dir$field"
    if [ ! -s "$file" ]; then
      echo "missing or empty: $file" >&2
      status=1
      continue
    fi
    # Characters, not bytes (Spanish copy is multibyte); the trailing newline
    # is not part of the field.
    count="$(LC_ALL=en_US.UTF-8 tr -d '\n' < "$file" | LC_ALL=en_US.UTF-8 wc -m | tr -d ' ')"
    max="$(limit "$field")"
    if [ "$count" -gt "$max" ]; then
      echo "$locale/$field is $count characters; Play allows $max" >&2
      status=1
    fi
  done
done
[ "$status" -eq 0 ] && echo "listing OK"
exit "$status"
