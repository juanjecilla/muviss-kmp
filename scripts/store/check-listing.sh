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
# The locales the listing ships in; one going missing is not "nothing to check".
for locale in en-US es-ES; do
  if [ ! -d "fastlane/metadata/android/$locale" ]; then
    echo "missing listing locale: $locale" >&2
    status=1
  fi
done
for dir in fastlane/metadata/android/*/; do
  locale="$(basename "$dir")"
  for field in title.txt short_description.txt full_description.txt; do
    file="$dir$field"
    if [ ! -s "$file" ]; then
      echo "missing or empty: $file" >&2
      status=1
      continue
    fi
    # Characters, not bytes (Spanish copy is multibyte); newlines are not part
    # of the field. Decoded explicitly: `wc -m` counts bytes when the host has
    # no UTF-8 locale, and PlayListingLimitsTest counts code points.
    count="$(python3 -c 'import sys; print(len(sys.stdin.buffer.read().decode("utf-8").replace("\n", "")))' < "$file")"
    max="$(limit "$field")"
    if [ "$count" -eq 0 ]; then
      echo "missing or empty: $file" >&2
      status=1
    elif [ "$count" -gt "$max" ]; then
      echo "$locale/$field is $count characters; Play allows $max" >&2
      status=1
    fi
  done
done
[ "$status" -eq 0 ] && echo "listing OK"
exit "$status"
