#!/bin/bash
# The owner-only setup behind the release pipeline (ADR 0024, ADR 0025): the
# accounts, secrets, rules and Play Console steps no PR can perform.
#
# Each step checks whether it is already done and skips if so, so the wizard is
# safe to re-run and resumes where you stopped. What `gh` can do, it does for
# you after asking. What only a browser can do, it opens and waits for.
# Progress for steps it cannot verify is kept in .git/owner-setup.done.
#
# Usage:  scripts/release/owner-setup.sh          # walk every step
#         scripts/release/owner-setup.sh --list   # show status only
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 1

REPO="$(gh repo view --json nameWithOwner --jq .nameWithOwner)" || { echo "gh is not authenticated"; exit 1; }
DONE_FILE="$(git rev-parse --git-dir)/owner-setup.done"
touch "$DONE_FILE"
LIST_ONLY="${1:-}"

bold() { printf '\n\033[1m%s\033[0m\n' "$*"; }
ok() { printf '  \033[32m✓\033[0m %s\n' "$*"; }
todo() { printf '  \033[33m•\033[0m %s\n' "$*"; }
say() { printf '    %s\n' "$*"; }
open_url() { say "→ $1"; command -v open >/dev/null && open "$1" 2>/dev/null; }
confirm() { read -r -p "    $1 [y/N] " a; [[ "$a" =~ ^[Yy] ]]; }
wait_enter() { read -r -p "    Press Enter when done (or type 'skip')... " a; [ "$a" != skip ]; }
marked() { grep -qx "$1" "$DONE_FILE"; }
mark() { marked "$1" || echo "$1" >>"$DONE_FILE"; }
has_secret() { gh secret list --repo "$REPO" --json name --jq '.[].name' | grep -qx "$1"; }
has_var() { gh variable list --repo "$REPO" --json name --jq '.[].name' | grep -qx "$1"; }

# step <title> <check-fn> <do-fn>
step() {
  local title="$1" check="$2" action="$3"
  if "$check"; then ok "$title"; return; fi
  todo "$title"
  [ "$LIST_ONLY" = "--list" ] && return
  if "$action" && "$check"; then ok "$title — done"; else say "(not done; re-run the wizard to continue)"; fi
}

# --- 1. develop as the default branch -------------------------------------
check_develop() { [ "$(gh api "repos/$REPO" --jq .default_branch)" = develop ]; }
do_develop() {
  say "Git flow: day-to-day work lands on develop; main only takes release/hotfix merges."
  confirm "Create develop from main (if missing) and make it the default branch?" || return 1
  if ! gh api "repos/$REPO/branches/develop" >/dev/null 2>&1; then
    gh api -X POST "repos/$REPO/git/refs" -f ref=refs/heads/develop \
      -f sha="$(gh api "repos/$REPO/git/ref/heads/main" --jq .object.sha)" >/dev/null || return 1
  fi
  gh api -X PATCH "repos/$REPO" -f default_branch=develop \
    -F allow_squash_merge=true -F allow_merge_commit=true -F allow_rebase_merge=false \
    -F delete_branch_on_merge=true >/dev/null
}

# --- 2. labels the workflows use --------------------------------------------
check_labels() { gh label list --repo "$REPO" --limit 200 --json name --jq '.[].name' | grep -qx no-landing; }
do_labels() {
  gh label create no-landing --repo "$REPO" --color c5def5 \
    --description "feat PR with nothing for the landing (paid, invisible, not user-facing)" >/dev/null
}

# --- 3. CodeRabbit ------------------------------------------------------------
check_coderabbit() { marked coderabbit; }
do_coderabbit() {
  say "CodeRabbit is the approving reviewer the rulesets count (free for public repos)."
  say "Install it for this repository only; .coderabbit.yaml in the repo configures it."
  open_url "https://github.com/apps/coderabbitai/installations/new"
  wait_enter && mark coderabbit
}

# --- 4. the release GitHub App -------------------------------------------------
check_app() { has_secret RELEASE_APP_ID && has_secret RELEASE_APP_PRIVATE_KEY; }
do_app() {
  say "The train, RC tags and back-merge PRs need an identity whose pushes start"
  say "workflows (GITHUB_TOKEN's do not). Create a private GitHub App:"
  say "  name: muviss-release   homepage: https://github.com/$REPO   webhook: off"
  say "  repository permissions: Contents RW, Pull requests RW, Issues RW,"
  say "                          Workflows RW, Metadata R"
  say "  where can it be installed: only this account"
  say "Then: generate a private key (.pem), and install the App on $REPO only."
  open_url "https://github.com/settings/apps/new"
  wait_enter || return 1
  read -r -p "    App ID (numeric, on the App's page): " app_id
  read -r -p "    Path to the downloaded .pem: " pem
  [ -n "$app_id" ] && [ -f "$pem" ] || { say "need both"; return 1; }
  gh secret set RELEASE_APP_ID --repo "$REPO" --body "$app_id"
  gh secret set RELEASE_APP_PRIVATE_KEY --repo "$REPO" <"$pem"
  gh variable set RELEASE_APP_ID --repo "$REPO" --body "$app_id"   # the rulesets need it unmasked
  say "Delete the .pem from your downloads once it is in a password manager."
}

# --- 5. rulesets ---------------------------------------------------------------
check_rulesets() {
  local names
  names="$(gh api "repos/$REPO/rulesets" --jq '.[].name' 2>/dev/null)"
  grep -qx main <<<"$names" && grep -qx develop <<<"$names" && grep -qx release-tags <<<"$names"
}
ruleset_branch() { # name ref merge_methods checks_json
  cat <<JSON
{
  "name": "$1", "target": "branch", "enforcement": "active",
  "conditions": { "ref_name": { "include": ["$2"], "exclude": [] } },
  "bypass_actors": [ { "actor_id": 5, "actor_type": "RepositoryRole", "bypass_mode": "always" } ],
  "rules": [
    { "type": "deletion" },
    { "type": "non_fast_forward" },
    { "type": "pull_request", "parameters": {
        "required_approving_review_count": 1,
        "dismiss_stale_reviews_on_push": false,
        "require_code_owner_review": false,
        "require_last_push_approval": false,
        "required_review_thread_resolution": false,
        "allowed_merge_methods": $3 } },
    { "type": "required_status_checks", "parameters": {
        "strict_required_status_checks_policy": false,
        "required_status_checks": $4 } }
  ]
}
JSON
}
do_rulesets() {
  local app_id
  app_id="$(gh variable get RELEASE_APP_ID --repo "$REPO" 2>/dev/null)"
  [ -n "$app_id" ] || { say "Do the release App step first (its ID goes on the tag ruleset)."; return 1; }
  say "main:    PR + 1 approval (CodeRabbit), merge commits only, CI + main guards"
  say "develop: PR + 1 approval, squash (features) or merge (back-merges), CI + landing guard"
  say "v* tags: only admins and the release App may create, move or delete them"
  say "Admins (you) can bypass all three."
  confirm "Apply the three rulesets?" || return 1
  local c='{"context":"build"},{"context":"desktop"},{"context":"ios"},{"context":"Release tooling tests"}'
  ruleset_branch main refs/heads/main '["merge"]' \
    "[$c,{\"context\":\"PRs into main come from release/* or hotfix/*\"},{\"context\":\"Release notes are final\"}]" \
    | gh api -X POST "repos/$REPO/rulesets" --input - >/dev/null || return 1
  ruleset_branch develop refs/heads/develop '["squash","merge"]' \
    "[$c,{\"context\":\"Features reach the landing\"}]" \
    | gh api -X POST "repos/$REPO/rulesets" --input - >/dev/null || return 1
  cat <<JSON | gh api -X POST "repos/$REPO/rulesets" --input - >/dev/null
{
  "name": "release-tags", "target": "tag", "enforcement": "active",
  "conditions": { "ref_name": { "include": ["refs/tags/v*"], "exclude": [] } },
  "bypass_actors": [
    { "actor_id": 5, "actor_type": "RepositoryRole", "bypass_mode": "always" },
    { "actor_id": $app_id, "actor_type": "Integration", "bypass_mode": "always" }
  ],
  "rules": [ { "type": "creation" }, { "type": "update" }, { "type": "deletion" } ]
}
JSON
}

# --- 6. the production environment ----------------------------------------------
check_env() { [ "$(gh api "repos/$REPO/environments/production" --jq '[.protection_rules[]?|select(.type=="required_reviewers")]|length' 2>/dev/null)" = 1 ]; }
do_env() {
  say "release.yml's promote job waits in the 'production' environment for your approval."
  confirm "Create it with you as the required reviewer?" || return 1
  local me
  me="$(gh api user --jq .id)"
  printf '{"reviewers":[{"type":"User","id":%s}],"deployment_branch_policy":null}' "$me" \
    | gh api -X PUT "repos/$REPO/environments/production" --input - >/dev/null
}

# --- 7. release keystore -----------------------------------------------------------
check_keystore() { has_secret KEYSTORE_BASE64 && has_secret RELEASE_STORE_PASSWORD && has_secret RELEASE_KEY_ALIAS && has_secret RELEASE_KEY_PASSWORD; }
do_keystore() {
  say "The upload key Play App Signing will trust (docs/RELEASING.md §1). Keep the"
  say "file and passwords in a password manager — losing them means a key reset request."
  local ks
  read -r -p "    Path to an existing keystore (empty = generate ~/muviss-upload.jks): " ks
  if [ -z "$ks" ]; then
    ks="$HOME/muviss-upload.jks"
    keytool -genkeypair -v -keystore "$ks" -alias upload -keyalg RSA -keysize 4096 -validity 10000 || return 1
  fi
  [ -f "$ks" ] || { say "no such file: $ks"; return 1; }
  local alias sp kp
  read -r -s -p "    Keystore password: " sp; echo
  # Read the alias out of the file instead of asking: a typed alias that does
  # not match fails only at signReleaseBundle, minutes into a CI build.
  alias="$(keytool -list -keystore "$ks" -storepass "$sp" 2>/dev/null | awk -F, '/PrivateKeyEntry/ {print $1; exit}')"
  [ -n "$alias" ] || { say "could not open $ks with that password, or it holds no private key"; return 1; }
  say "key alias in $ks: $alias"
  # keytool writes PKCS12, where the key password is the store password; a
  # separate prompt only invites a mismatch ("Given final block not properly
  # padded" at signing). Ask only for a JKS file, and verify it here.
  kp="$sp"
  if ! keytool -exportcert -keystore "$ks" -storepass "$sp" -alias "$alias" -keypass "$kp" >/dev/null 2>&1; then
    read -r -s -p "    Key password (differs from the store's): " kp; echo
    keytool -exportcert -keystore "$ks" -storepass "$sp" -alias "$alias" -keypass "$kp" >/dev/null 2>&1 \
      || { say "that key password does not open '$alias'"; return 1; }
  fi
  base64 <"$ks" | tr -d '\n' | gh secret set KEYSTORE_BASE64 --repo "$REPO"
  gh secret set RELEASE_STORE_PASSWORD --repo "$REPO" --body "$sp"
  gh secret set RELEASE_KEY_ALIAS --repo "$REPO" --body "$alias"
  gh secret set RELEASE_KEY_PASSWORD --repo "$REPO" --body "$kp"
  say "For local release builds, put RELEASE_STORE_FILE=$ks and the three values in local.properties."
}

# --- 8. Play: the app, the first upload, Play App Signing ------------------------
check_play_app() { marked play-app; }
do_play_app() {
  say "The Play API cannot create an app or its first release, so this one is by hand:"
  say "  1. Create app 'Muviss' (default language en-US, App, Free)."
  say "  2. Build a signed AAB locally:  ./gradlew :app:androidApp:bundleRelease"
  say "     (needs the keystore values in local.properties; full git clone)"
  say "  3. Testing → Internal testing → Create release → upload the AAB."
  say "     Accept Play App Signing when offered (Google keeps the app key; yours is the upload key)."
  say "  4. Add yourself as an internal tester and install from the opt-in link."
  open_url "https://play.google.com/console/developers"
  wait_enter && mark play-app
}

# --- 9. Play service account ----------------------------------------------------------
check_play_sa() { has_secret PLAY_SERVICE_ACCOUNT_JSON; }
do_play_sa() {
  say "fastlane uploads and promotes with a service account (docs/RELEASING.md §5):"
  say "  1. Google Cloud: create a service account in a project of yours; create a JSON key."
  say "  2. Play Console → Users and permissions → Invite the service account's email,"
  say "     app: Muviss, permissions: Release to production, Release apps to testing tracks,"
  say "     Manage testing tracks, Edit store listing."
  open_url "https://console.cloud.google.com/iam-admin/serviceaccounts"
  wait_enter || return 1
  local json
  read -r -p "    Path to the service account JSON key: " json
  [ -f "$json" ] || { say "no such file"; return 1; }
  gh secret set PLAY_SERVICE_ACCOUNT_JSON --repo "$REPO" <"$json"
  say "Delete the key file once it is stored safely."
}

# --- 10. Play Console forms -------------------------------------------------------------
check_play_forms() { marked play-forms; }
do_play_forms() {
  say "Policy → App content, each from the repo's own answers:"
  say "  Privacy policy:  https://muvissapp.com/privacy/"
  say "  Data safety:     docs/store/DATA_SAFETY.md"
  say "  Ads: no · Target audience: 13+ (no children) · Content rating questionnaire"
  say "  Category: Entertainment · contact email · no account (local-only v1, ADR 0024)"
  open_url "https://play.google.com/console/developers"
  wait_enter && mark play-forms
}

# --- 11. optional secrets ---------------------------------------------------------------
check_optional() { has_secret TMDB_API_KEY && has_secret SENTRY_DSN; }
do_optional() {
  say "Release builds without TMDB_API_KEY cannot search; without SENTRY_DSN crash reporting no-ops."
  say "Sentry mapping upload also wants SENTRY_AUTH_TOKEN, SENTRY_ORG, SENTRY_PROJECT."
  local name val
  for name in TMDB_API_KEY TMDB_READ_TOKEN SENTRY_DSN SENTRY_AUTH_TOKEN SENTRY_ORG SENTRY_PROJECT; do
    has_secret "$name" && continue
    read -r -s -p "    $name (empty = skip): " val; echo
    [ -z "$val" ] || gh secret set "$name" --repo "$REPO" --body "$val"
  done
}

bold "Muviss release setup — $REPO"
step "develop is the default branch"                       check_develop    do_develop
step "the no-landing label exists"                         check_labels     do_labels
step "CodeRabbit is installed"                             check_coderabbit do_coderabbit
step "the muviss-release GitHub App's secrets are set"      check_app        do_app
step "rulesets protect main, develop and v* tags"          check_rulesets   do_rulesets
step "the production environment needs your approval"      check_env        do_env
step "the release keystore secrets are set"                check_keystore   do_keystore
step "TMDB and Sentry secrets are set"                     check_optional   do_optional
step "Play: app created, first AAB uploaded, App Signing"  check_play_app   do_play_app
step "Play: service account secret is set"                 check_play_sa    do_play_sa
step "Play: App content forms are complete"                check_play_forms do_play_forms

bold "Then"
say "Cut the first candidate:  scripts/release/start-release.sh 1.0.0"
say "After Play approves the first production release, set PLAY_RELEASE_STATUS:"
say "  gh variable set PLAY_RELEASE_STATUS --body completed"
