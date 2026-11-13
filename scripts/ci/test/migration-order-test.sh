#!/bin/bash
# Tests for scripts/ci/check-migration-order.sh (#727), against throwaway git repos.
#   scripts/ci/test/migration-order-test.sh
set -uo pipefail
CI="$(cd "$(dirname "$0")/.." && pwd)"
failures=0
pass() { printf '  \033[32mok\033[0m   %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; failures=$((failures + 1)); }
expect_eq() { if [ "$2" = "$3" ]; then pass "$1"; else fail "$1: expected '$3', got '$2'"; fi; }

# A repo whose develop has one migration, with a feature branch cut from it.
new_repo() {
  local dir
  dir="$(mktemp -d)"
  git -C "$dir" init -q -b develop
  git -C "$dir" config user.email t@example.com
  git -C "$dir" config user.name test
  git -C "$dir" config commit.gpgsign false
  mkdir -p "$dir/supabase/migrations"
  echo "-- base" >"$dir/supabase/migrations/20261027000000_taste_profile.sql"
  git -C "$dir" add -A
  git -C "$dir" commit -q -m "chore: init"
  echo "$dir"
}
add_on() { # <repo> <branch> <file>  adds a migration on a branch, ready to check
  git -C "$1" switch -q -c "$2" develop 2>/dev/null || git -C "$1" switch -q "$2"
  echo "-- $3" >"$1/supabase/migrations/$3"
  git -C "$1" add -A
  git -C "$1" commit -q -m "feat(sync): $3"
}
check() { (cd "$1" && "$CI/check-migration-order.sh" develop >/dev/null 2>&1); echo $?; }

echo "check-migration-order.sh (#727)"

r="$(new_repo)"
git -C "$r" switch -q -c nothing develop
expect_eq "a change that adds no migration passes" "$(check "$r")" "0"

r="$(new_repo)"; add_on "$r" after 20261101000000_cowatch.sql
expect_eq "a migration after develop's newest passes" "$(check "$r")" "0"

r="$(new_repo)"; add_on "$r" before 20261020000000_held.sql
expect_eq "a migration before develop's newest fails" "$(check "$r")" "1"

r="$(new_repo)"; add_on "$r" same 20261027000000_same.sql
expect_eq "a timestamp equal to develop's newest fails (strictly after)" "$(check "$r")" "1"

r="$(new_repo)"; add_on "$r" noprefix add_something.sql
expect_eq "a name with no 14-digit timestamp fails" "$(check "$r")" "1"

# The held migration: the branch is cut before develop gains a newer one, so its own
# timestamp is later than the branch base but earlier than develop's newest now.
r="$(new_repo)"; add_on "$r" held 20261030000000_held.sql
git -C "$r" switch -q develop
echo "-- later" >"$r/supabase/migrations/20261101000000_later.sql"; git -C "$r" add -A; git -C "$r" commit -q -m "feat(x): later"
git -C "$r" switch -q held
expect_eq "a branch overtaken by develop's newer migration fails" "$(check "$r")" "1"

# A migration already on develop is not an addition: a rename of it is not judged here.
r="$(new_repo)"; git -C "$r" switch -q -c touch develop
echo "-- edited" >>"$r/supabase/migrations/20261027000000_taste_profile.sql"; git -C "$r" add -A; git -C "$r" commit -q -m "fix(x): edit"
expect_eq "an edit to a migration already on develop passes" "$(check "$r")" "0"

# An unreadable base fails closed rather than passing every file.
r="$(new_repo)"; add_on "$r" after 20261101000000_cowatch.sql
expect_eq "a base that is not a commit fails closed" "$(cd "$r" && "$CI/check-migration-order.sh" origin/missing >/dev/null 2>&1; echo $?)" "1"

echo
if [ "$failures" -eq 0 ]; then echo "all passed"; else echo "$failures failed"; exit 1; fi
