# shellcheck shell=bash
# Sourced by the test scripts (#1018), not run. file_mode FILE prints the octal
# permission bits of FILE (600, 644, ...) on both GNU and BSD/macOS.
# GNU stat first: on GNU, `stat -f` succeeds and prints filesystem data, so the
# BSD form must not be tried first. On BSD/macOS `stat -c` is an error.
file_mode() { stat -c %a "$1" 2>/dev/null || stat -f %Lp "$1"; }
