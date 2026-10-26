#!/bin/bash
# Writes the Play release notes for one version, for every listing locale
# (fastlane/release-notes/v<X.Y.Z>/<locale>.txt), from the conventional commits
# since the previous release. Notes never block a release: the train writes them
# when it cuts the branch (start-release.sh) and rewrites them on every release
# candidate (release-rc.yml), so a fix merged into the branch is in them too.
#
# What reaches users: `feat` and `fix` subjects, minus work no store user can
# see. That is infrastructure scopes (release, ci, build, ops, supabase, ...),
# other platforms (ios, web, desktop), and paid or remotely-dark features
# (sync, co-watch, the own TMDB key), which never appear in public copy.
# English lists the changes; other locales get a sentence that counts them,
# since a commit subject cannot be translated here.
#
# A file a person has edited is theirs: `.generated` records the hash of what
# this script last wrote, and a file that no longer matches it is left alone.
# A file that still starts with the old `DRAFT:` marker is replaced.
#
# Usage: scripts/release/release-notes.sh <X.Y.Z> [--write]   (prints without --write)
set -euo pipefail
cd "$(dirname "$0")/../.." || exit 1

VERSION="${1:?usage: $0 <X.Y.Z> [--write]}"
WRITE="${2:-}"
MAX=500
DIR="fastlane/release-notes/v$VERSION"
LEDGER="$DIR/.generated"

# Scopes and phrases that never reach a store listing. Keep in step with what
# CLAUDE.md says is paid (sync, co-watch) or dark (BYOK, remote config).
HIDDEN_SCOPES='release|ci|build|deps|ops|supabase|sync|cowatch|billing|config|ios|web|desktop|docs|test|landing|website|store'
HIDDEN_WORDS='sync|co-watch|cowatch|paywall|sign-in|sign in|signed-out|signed out|account|entitlement|tmdb key|own key|byok'

# What is new since the release before this one, final or still in flight:
# 1.3.0's notes must not repeat 1.2.0's while 1.2.0 is still in beta.
read -r _ FROM <<<"$(scripts/release/previous-release.sh HEAD "$VERSION")" || true
range="${FROM:+$FROM..}HEAD"

items() { # <feat|fix> -> one user-facing line per change
  git log --no-merges --format='%s' "$range" \
    | grep -E "^$1(\([^)]*\))?!?: " \
    | grep -vE "^$1\(($HIDDEN_SCOPES)\)" \
    | grep -viE "$HIDDEN_WORDS" \
    | sed -E "s/^$1(\([^)]*\))?!?: //; s/( \((#[0-9]+|(EPIC|ADR|WS|Wave) [^)]*)\))+$//; s/^#[0-9]+[[:space:]]*//; s/ #[0-9]+([^0-9]|$)/\1/g" \
    | awk '{ print toupper(substr($0, 1, 1)) substr($0, 2) }' \
    | awk '!seen[$0]++' || true
}
features="$(items feat)"
fixes="$(items fix)"
count() { [ -n "$1" ] && grep -c . <<<"$1" || echo 0; }
n_feat="$(count "$features")"
n_fix="$(count "$fixes")"

english() {
  local out="What's new in Muviss $VERSION:" line tail="- Bug fixes and improvements."
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    local next="$out"$'\n'"- $line"
    [ "$(printf '%s\n%s\n' "$next" "$tail" | wc -m | tr -d ' ')" -le "$MAX" ] || break
    out="$next"
  done <<<"$(printf '%s\n%s\n' "$features" "$fixes")"
  printf '%s\n%s\n' "$out" "$tail"
}

spanish() {
  local feat="novedades" fix="correcciones"
  [ "$n_feat" -eq 1 ] && feat="novedad"
  [ "$n_fix" -eq 1 ] && fix="corrección"
  if [ "$n_feat" -gt 0 ] && [ "$n_fix" -gt 0 ]; then
    echo "Muviss $VERSION trae $n_feat $feat y $n_fix $fix de errores."
  elif [ "$n_feat" -gt 0 ]; then
    echo "Muviss $VERSION trae $n_feat $feat y mejoras."
  elif [ "$n_fix" -gt 0 ]; then
    echo "Muviss $VERSION trae $n_fix $fix de errores y mejoras."
  else
    echo "Muviss $VERSION trae mejoras y correcciones de errores."
  fi
}

notes_for() { # <locale>
  # A listing language with no sentence of its own gets the English list: it is
  # what Play shows for an untranslated locale anyway, and the count sentence
  # is Spanish. A new language adds its own branch here.
  case "$1" in
    es-*) spanish ;;
    en-* | *) english ;;
  esac
}

hash_of() { git hash-object --stdin; }
recorded() { [ -f "$LEDGER" ] && awk -v l="$1" '$1 == l { print $2 }' "$LEDGER" || true; }

[ -d fastlane/metadata/android ] || { echo "no Play listing; nothing to write"; exit 0; }
locales="$(find fastlane/metadata/android -mindepth 1 -maxdepth 1 -type d -exec basename {} \; | sort)"

if [ "$WRITE" != "--write" ]; then
  for locale in $locales; do echo "== $locale"; notes_for "$locale"; done
  exit 0
fi

mkdir -p "$DIR"
ledger=""
for locale in $locales; do
  file="$DIR/$locale.txt"
  if [ -f "$file" ] && ! head -1 "$file" | grep -q '^DRAFT:' \
    && [ "$(hash_of <"$file")" != "$(recorded "$locale")" ]; then
    echo "kept $file (edited by hand)"
    continue
  fi
  notes_for "$locale" >"$file"
  ledger+="$locale $(hash_of <"$file")"$'\n'
  echo "wrote $file"
done
# Locales kept by hand drop out of the ledger, so they stay theirs.
printf '%s' "$ledger" >"$LEDGER"
