#!/bin/bash
# Creates or updates the Feature Request form on Tally from tally-form.json (ADR 0035).
# Owner-only: TALLY_API_KEY (Tally -> Settings -> API keys) never ships in the app.
#   scripts/feedback/tally-form.sh create            # makes a DRAFT, prints its URL
#   scripts/feedback/tally-form.sh update <form-id>  # PATCHes the definition, keeping it a draft
#   scripts/feedback/tally-form.sh publish <form-id> # PATCHes the definition with status PUBLISHED
# Publishing sends the blocks too: a status-only PATCH left the public page with zero blocks
# (just a Submit button), because Tally keeps draft blocks apart until a PATCH carries them.
# TALLY_API_BASE overrides https://api.tally.so (the tests point it at a stub).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
DEFINITION="${TALLY_FORM_DEFINITION:-$HERE/tally-form.json}"
BASE="${TALLY_API_BASE:-https://api.tally.so}"
: "${TALLY_API_KEY:?set TALLY_API_KEY (Tally -> Settings -> API keys)}"

# The key goes to curl through a 0600 header file, never on its command line, where any
# local process could read it from a process listing.
headers="$(umask 077 && mktemp)"
trap 'rm -f "$headers"' EXIT
printf 'Authorization: Bearer %s\n' "$TALLY_API_KEY" > "$headers"

call() { # method path [body-file]
  local method="$1" path="$2" body="${3:-}"
  local args=(-sS -X "$method" "$BASE$path" -H "@$headers" -H "Content-Type: application/json" -w '\n%{http_code}')
  [ -n "$body" ] && args+=(--data-binary "@$body")
  local out code
  out="$(curl "${args[@]}")"
  code="${out##*$'\n'}"
  out="${out%$'\n'*}"
  if [ "${code:0:1}" != "2" ]; then
    echo "Tally answered HTTP $code: $out" >&2
    exit 1
  fi
  printf '%s' "$out"
}

form_id() { sed -n 's/.*"id" *: *"\([^"]*\)".*/\1/p' | head -1; }

case "${1:-}" in
  create)
    id="$(call POST /forms "$DEFINITION" | form_id)"
    [ -n "$id" ] || { echo "No form id in Tally's answer" >&2; exit 1; }
    echo "Created DRAFT form $id"
    echo "Public URL (once published): https://tally.so/r/$id"
    echo "Next: open it in the Tally dashboard and check the four hidden fields and the email notification,"
    echo "then: $0 publish $id, and set FEATURE_REQUEST_URL (local.properties / CI) or ConfigCat featureRequestUrl."
    ;;
  update)
    id="${2:?usage: update <form-id>}"
    call PATCH "/forms/$id" "$DEFINITION" >/dev/null
    echo "Updated form $id from $DEFINITION"
    ;;
  publish)
    id="${2:?usage: publish <form-id>}"
    tmp="$(mktemp)"
    sed 's/"status": *"[A-Z]*"/"status": "PUBLISHED"/' "$DEFINITION" > "$tmp"
    call PATCH "/forms/$id" "$tmp" >/dev/null; rm -f "$tmp"
    echo "Published https://tally.so/r/$id"
    ;;
  *)
    echo "usage: $0 create | update <form-id> | publish <form-id>" >&2
    exit 2
    ;;
esac
