#!/bin/bash
# Runs a command and samples the machine's memory while it runs (#829).
#
# Every MEMORY_SAMPLE_INTERVAL seconds (default 15) it prints the system's free
# memory percentage and the five largest resident processes, so a run that dies
# with "Gradle build daemon disappeared unexpectedly" shows which process held
# the RAM and how close the machine was to the edge. On exit it prints a
# one-line summary: the lowest free percentage and the largest process RSS seen.
#
# Usage: scripts/ios/with-memory-sample.sh <command> [args...]
# Exits with the command's status. Written for macOS (memory_pressure, ps); the
# free-percentage column reads n/a where memory_pressure is missing.
set -uo pipefail

if [ "$#" -eq 0 ]; then
  echo "usage: $0 <command> [args...]" >&2
  exit 2
fi

interval="${MEMORY_SAMPLE_INTERVAL:-15}"
log="$(mktemp "${TMPDIR:-/tmp}/muviss-memory.XXXXXX")"

sample() {
  while true; do
    now="$(date -u +%H:%M:%S)"
    free=""
    if command -v memory_pressure >/dev/null 2>&1; then
      free="$(memory_pressure 2>/dev/null | sed -n 's/.*free percentage: \([0-9]*\)%.*/\1/p' | head -n 1)"
    fi
    top="$(ps -axo rss=,comm= | sort -rn | head -n 5)"
    echo "memory $now free%=${free:-n/a}"
    while read -r rss comm; do
      echo "memory $now rss_mb=$((rss / 1024)) $comm"
    done <<< "$top"
    # One machine-readable line per sample for the summary: time, free%, the
    # largest RSS in KB.
    echo "$now ${free:-} $(head -n 1 <<< "$top" | awk '{ print $1 }')" >> "$log"
    sleep "$interval"
  done
}

sample &
sampler=$!
trap 'kill "$sampler" 2>/dev/null; wait "$sampler" 2>/dev/null' EXIT

"$@"
status=$?

kill "$sampler" 2>/dev/null
wait "$sampler" 2>/dev/null
trap - EXIT

# The command may run for less than one interval; the summary then says so
# rather than reporting nothing.
count="$(wc -l < "$log" | tr -d ' ')"
lowest="$(awk '$2 != "" { if (min == "" || $2 + 0 < min + 0) min = $2 } END { if (min != "") print min "%" }' "$log")"
peak="$(awk '$3 != "" { if ($3 + 0 > max) max = $3 + 0 } END { if (max != "") printf "%d MB", max / 1024 }' "$log")"
echo "memory summary: lowest free=${lowest:-n/a}, largest process RSS=${peak:-n/a}, over $count samples"
rm -f "$log"
exit "$status"
