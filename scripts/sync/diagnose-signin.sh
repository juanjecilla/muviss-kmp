#!/bin/bash
# Why did OAuth sign-in not complete?
#
# The flow crosses three boundaries — app, browser, GoTrue — and each fails
# differently while looking identical from the profile screen. This walks them
# in order so the answer falls out of which step is the first to disagree.
#
# The failures seen so far, and what they look like here:
#
#   * Provider not enabled in Supabase   -> step 1 answers 4xx, not a 302.
#   * bad_oauth_state / state expired    -> the redirect never reaches the app
#                                           (no START line in step 3) and the
#                                           browser lands on site_url instead.
#                                           GoTrue's flow state lives 5 minutes.
#   * App killed during the browser trip -> "Start proc" in step 2; the PKCE
#                                           verifier is held in memory only, so
#                                           the exchange then has nothing to
#                                           prove the code with.
#   * Exchange ran and failed            -> step 3 shows the redirect arriving,
#                                           the process never restarted, and
#                                           step 4 still shows no session.
set -u
cd "$(dirname "$0")/../.." || exit 1
# shellcheck source=scripts/sync/lib.sh
. scripts/sync/lib.sh

echo "=== 1. is the provider enabled? (expect 302 to the provider) ==="
PROVIDER="${1:-github}"
curl -s -o /dev/null -D - \
  "$(rest_url | sed 's|/rest/v1||')/auth/v1/authorize?provider=$PROVIDER&redirect_to=muviss%3A%2F%2Fauth-callback&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=s256" \
  | grep -iE '^HTTP/|^location:' | sed -E 's/(client_id=)[^&]*/\1<redacted>/'

echo
echo "=== 2. did the app process survive the browser trip? ==="
echo "(a 'Start proc' line after you tapped sign-in means it was killed and the in-memory PKCE verifier went with it)"
adb_ logcat -d 2>&1 | grep -iE "Start proc.*$PKG" | tail -5 || echo "  no restarts logged"

echo
echo "=== 3. did the redirect reach MainActivity? ==="
adb_ logcat -d 2>&1 | grep -E "START .*muviss://auth-callback" | tail -5 || echo "  NO — the redirect never arrived (see step 1, or the state expired)"

echo
echo "=== 4. is there a session now? ==="
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
DB=$(pull_database "$WORK")
sqlite3 "$DB" "SELECT CASE WHEN accessToken IS NULL THEN '  no session stored'
  ELSE '  signed in as '||COALESCE(email,'(no email)') END FROM syncAccount;"

echo
echo "=== 5. crashes ==="
adb_ logcat -d -b crash 2>&1 | grep -m3 -E "FATAL|Exception" || echo "  none"
