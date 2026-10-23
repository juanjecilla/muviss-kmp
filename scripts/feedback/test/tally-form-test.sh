#!/bin/bash
# Tests for tally-form.sh against a stub curl; no network.
#   scripts/feedback/test/tally-form-test.sh
set -uo pipefail
SCRIPT="$(cd "$(dirname "$0")/.." && pwd)/tally-form.sh"
failures=0
pass() { printf '  \033[32mok\033[0m   %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; failures=$((failures + 1)); }
expect_eq() { [ "$2" = "$3" ] && pass "$1" || fail "$1: expected '$3', got '$2'"; }

bin="$(mktemp -d)"; log="$bin/calls.log"
# The stub records method, URL and body, and answers what CURL_STATUS / CURL_BODY say.
cat > "$bin/curl" <<'STUB'
#!/bin/bash
method=""; url=""; body=""
while [ $# -gt 0 ]; do
  case "$1" in
    -X) method="$2"; shift ;;
    # GNU stat first: on Linux `stat -f` is file-system mode and "succeeds" with no mode.
    -H) case "$2" in @*) echo "HEADERFILE $(cat "${2#@}") $(stat -c %a "${2#@}" 2>/dev/null || stat -f %Lp "${2#@}")" >> "$CALLS_LOG" ;; *Authorization*) echo "ARGKEY" >> "$CALLS_LOG" ;; esac; shift ;;
    --data-binary) body="$(cat "${2#@}")" ;;
    http*) url="$1" ;;
  esac
  shift
done
echo "$method $url $body" >> "$CALLS_LOG"
printf '%s\n%s' "${CURL_BODY:-{\"id\":\"wA1b2C\"\}}" "${CURL_STATUS:-201}"
STUB
chmod +x "$bin/curl"
export PATH="$bin:$PATH" CALLS_LOG="$log" TALLY_API_KEY=test-key TALLY_API_BASE=https://stub.test

echo "tally-form.sh"
out="$("$SCRIPT" create)"; rc=$?
expect_eq "create exits 0" "$rc" "0"
case "$out" in *"https://tally.so/r/wA1b2C"*) pass "create prints the public URL from the returned id" ;; *) fail "create prints the URL: $out" ;; esac
grep -q '^POST https://stub.test/forms ' "$log" && pass "create POSTs /forms" || fail "create POSTs /forms"
grep -q '"HIDDEN_FIELDS"' "$log" && pass "create sends the checked-in definition" || fail "create sends the definition"
grep -q '^HEADERFILE Authorization: Bearer test-key 600$' "$log" && pass "the key reaches curl through a 0600 header file" || fail "the key reaches curl through a 0600 header file: $(grep HEADERFILE "$log")"
grep -q '^ARGKEY' "$log" && fail "the key is on curl's command line" || pass "the key is not on curl's command line"
for f in version platform os locale; do
  grep -q "\"name\": *\"$f\"" "$log" && pass "definition carries hidden field $f" || fail "definition carries hidden field $f"
done

: > "$log"; CURL_BODY='{"id":"wA1b2C","status":"DRAFT"}' "$SCRIPT" update wA1b2C >/dev/null
grep -q '^PATCH https://stub.test/forms/wA1b2C ' "$log" && pass "update PATCHes the form" || fail "update PATCHes the form"
grep -q '"status": "DRAFT"' "$log" && pass "update of a draft stays a draft" || fail "update of a draft stays a draft"
: > "$log"; CURL_BODY='{"id":"wA1b2C","status":"PUBLISHED"}' "$SCRIPT" update wA1b2C >/dev/null
grep -q '^PATCH ' "$log" && grep -q '"status": "PUBLISHED"' "$log" && pass "update of a published form does not unpublish it" || fail "update of a published form does not unpublish it"
: > "$log"; if "$SCRIPT" update wA1b2C >/dev/null 2>&1; then fail "update without a readable status fails"; else pass "update without a readable status fails"; fi
grep -q '^PATCH ' "$log" && fail "update without a readable status PATCHes nothing" || pass "update without a readable status PATCHes nothing"
: > "$log"; if CURL_STATUS=500 "$SCRIPT" update wA1b2C >/dev/null 2>&1; then fail "update after a failed GET fails"; else pass "update after a failed GET fails"; fi
grep -q '^PATCH ' "$log" && fail "update after a failed GET PATCHes nothing" || pass "update after a failed GET PATCHes nothing"

: > "$log"; "$SCRIPT" publish wA1b2C >/dev/null
grep -q '^PATCH https://stub.test/forms/wA1b2C ' "$log" && grep -q '"status": "PUBLISHED"' "$log" && pass "publish PATCHes status PUBLISHED" || fail "publish PATCHes status"
grep -q '"HIDDEN_FIELDS"' "$log" && pass "publish sends the blocks too, not just the status" || fail "publish sends the blocks"

CURL_STATUS=401 CURL_BODY='{"message":"nope"}' "$SCRIPT" create >/dev/null 2>&1; expect_eq "a 401 fails" "$?" "1"
(unset TALLY_API_KEY; "$SCRIPT" create >/dev/null 2>&1); expect_eq "no key fails" "$?" "1"
"$SCRIPT" bogus >/dev/null 2>&1; expect_eq "unknown command exits 2" "$?" "2"
python3 -c "import json;json.load(open('$(dirname "$SCRIPT")/tally-form.json'))" && pass "definition is valid JSON" || fail "definition is valid JSON"

[ "$failures" -eq 0 ] && echo "all passed" || { echo "$failures failed"; exit 1; }
