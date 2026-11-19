#!/bin/bash
# Tests for scripts/ios/with-memory-sample.sh (#829). memory_pressure is faked
# on PATH, so the test runs on any host: a free percentage, a memory_pressure
# that prints no percentage, and a machine without memory_pressure at all.
#   scripts/ios/test/with-memory-sample-test.sh
set -uo pipefail
SCRIPTS="$(cd "$(dirname "$0")/.." && pwd)"
failures=0
pass() { printf '  \033[32mok\033[0m   %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; failures=$((failures + 1)); }
expect_eq() { [ "$2" = "$3" ] && pass "$1" || fail "$1: expected '$3', got '$2'"; }
expect_match() { [[ "$2" =~ $3 ]] && pass "$1" || fail "$1: '$2' does not match '$3'"; }

# Every throwaway directory lives under one parent the trap removes.
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# A bin directory with the tools the sampler uses and no memory_pressure.
BARE="$WORK/bare-bin"
mkdir -p "$BARE"
for tool in date sleep ps sort head sed awk wc tr rm mktemp cat sh; do
  path="$(command -v "$tool")" && ln -s "$path" "$BARE/$tool"
done

# A memory_pressure that reports a percentage, and one that reports none.
WITH_PCT="$WORK/pct-bin"
NO_PCT="$WORK/nopct-bin"
mkdir -p "$WITH_PCT" "$NO_PCT"
printf '#!/bin/sh\necho "System-wide memory free percentage: 42%%"\n' > "$WITH_PCT/memory_pressure"
printf '#!/bin/sh\necho "memory_pressure: no data"\n' > "$NO_PCT/memory_pressure"
chmod +x "$WITH_PCT/memory_pressure" "$NO_PCT/memory_pressure"

# Runs the sampler on a PATH built from the given bin directory, and prints its
# output. The command runs two seconds, so at least one sample lands.
run_sampler() {
  MEMORY_SAMPLE_INTERVAL=1 PATH="$1" TMPDIR="$WORK" "$SCRIPTS/with-memory-sample.sh" sleep 2 2>&1
}

echo "with a free percentage"
out="$(PATH="$WITH_PCT:/usr/bin:/bin" run_sampler "$WITH_PCT:/usr/bin:/bin")"
summary="$(grep '^memory summary:' <<< "$out")"
expect_match "summary reports lowest free from the percentage" "$summary" 'lowest free=42%'
expect_match "summary reports a peak RSS in MB" "$summary" 'largest process RSS=[0-9]+ MB'

echo "with memory_pressure printing no percentage"
out="$(run_sampler "$NO_PCT:/usr/bin:/bin")"
summary="$(grep '^memory summary:' <<< "$out")"
expect_match "lowest free is n/a, not the RSS column" "$summary" 'lowest free=n/a,'
expect_match "peak RSS is still read from the third field" "$summary" 'largest process RSS=[0-9]+ MB'

echo "with memory_pressure missing"
out="$(run_sampler "$BARE")"
summary="$(grep '^memory summary:' <<< "$out")"
expect_match "lowest free is n/a, not the RSS column" "$summary" 'lowest free=n/a,'
expect_match "peak RSS is still read from the third field" "$summary" 'largest process RSS=[0-9]+ MB'

echo "exit status"
PATH="$BARE" MEMORY_SAMPLE_INTERVAL=1 TMPDIR="$WORK" "$SCRIPTS/with-memory-sample.sh" sh -c 'exit 7' >/dev/null 2>&1
expect_eq "the command's status is returned" "$?" "7"

echo
if [ "$failures" -eq 0 ]; then
  echo "all memory-sample tests passed"
else
  echo "$failures memory-sample test(s) failed"
  exit 1
fi
