# Releasing Muviss

Everything needed to go from "the code builds" to "a signed AAB in hand" —
for a local machine and for CI. See ADR 0007 for the generated-build-config
pattern this reuses (TMDB key, Sentry DSN); EPICS.md / issue #6 for the wider
release-engineering scope this document is part of.

## 1. Release keystore

Muviss never generates or commits a real keystore — `local.properties` and
CI secrets are the only places one lives. To create one for yourself:

```bash
keytool -genkeypair -v \
  -keystore release.keystore.jks \
  -alias muviss \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storetype JKS
```

`keytool` will prompt for the store/key passwords and your distinguished
name (org, name, etc.) interactively. Store the resulting `.jks` file
**outside the repo** (it's already covered by `.gitignore` via
`*.jks`/`*.keystore` if you do put it inside, but outside is safer).

Then either:

- **Locally**: add to `local.properties` (gitignored):
  ```properties
  RELEASE_STORE_FILE=/absolute/path/to/release.keystore.jks
  RELEASE_STORE_PASSWORD=...
  RELEASE_KEY_ALIAS=muviss
  RELEASE_KEY_PASSWORD=...
  ```
- **CI**: base64-encode the keystore and store it plus the three passwords
  as repository secrets (`KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`,
  `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`) — see
  `.github/workflows/android-release-build.yml`. `scripts/release/owner-setup.sh`
  does this for you.
  ```bash
  base64 -i release.keystore.jks | pbcopy   # macOS; use -w0 on Linux
  ```

When none of the four values are present — the default for a fresh clone —
`app/androidApp/build.gradle.kts` falls back to **debug signing** for the
`release` build type, so `assembleRelease`/`bundleRelease` still work for
every contributor; they just don't produce a Play-Store-installable
artifact. CI's release build (`android-release-build.yml`) refuses that
fallback: it fails unless the AAB is signed and its certificate is not the
Android debug key, so a missing secret stops the RC instead of uploading a
debug-signed bundle. Both paths are exercised as part of this epic's verification (a
disposable throwaway `keytool` keystore was used to prove the signing path
works end-to-end, then discarded — never committed).

Play App Signing enrollment (letting Google re-sign the AAB with its own
key for distribution) happens once, by hand, in the Play Console on first
upload — nothing to configure in the repo for that.

## 2. R8 / minification

`isMinifyEnabled = true` and `isShrinkResources = true` are set on the
`release` build type. Keep rules live in `app/androidApp/proguard-rules.pro`,
each block commented with which library it's for and why the rule is
needed (most libraries — SQLDelight, Koin — ship their own consumer rules
and need nothing from us; Ktor's OkHttp engine and our own
`@Serializable` types do).

To verify R8 after touching a dependency or the rules file:

```bash
./gradlew :app:androidApp:assembleRelease
```

Check `app/androidApp/build/outputs/mapping/release/missing_rules.txt` —
if R8 generated one, something is being stripped that shouldn't be; if the
file doesn't exist, R8 had nothing to complain about. A cosmetic
`R8: An error occurred when parsing kotlin metadata` warning is expected on
this toolchain (R8 lags the newest Kotlin metadata version) and is
harmless — it's a warning about *reading* metadata for reflection helpers,
not a missing-class error.

### Baseline profile (EPIC 34 / #77)

`app/androidApp/src/release/generated/baselineProfiles/` holds the committed
baseline and startup profiles, packaged into release builds as
`assets/dexopt/baseline.prof` and installed by `profileinstaller` where Play's
cloud profiles have not reached yet. `:app:baselineprofile` generates them by
walking cold start and every bottom-bar tab (`BaselineProfileGenerator`).

It needs a device and is not in CI. Regenerate when startup or the main screens
change materially, on an emulator **of its own** — generation reinstalls and
recompiles the app, so never point it at a device whose Muviss data you care
about:

```bash
avdmanager create avd -n Muviss_Baseline -k "system-images;android-36;google_apis_playstore;arm64-v8a"
emulator -avd Muviss_Baseline -port 5556 -no-window &
ANDROID_SERIAL=emulator-5556 ./gradlew :app:androidApp:generateReleaseBaselineProfile
```

`ANDROID_SERIAL` matters: `useConnectedDevices` runs on every attached device
otherwise.

## 3. Versioning

`versionCode`/`versionName` are derived from git in
`app/androidApp/build.gradle.kts` — there is no hardcoded version anywhere:

- `versionCode` = `major·1 000 000 + minor·10 000 + patch·100 + rcN` on an
  exact release tag, `vX.Y.Z` or `vX.Y.Z-rcN` (so 1.1.0-rc4 is 1010004). A
  release code is unique per candidate and rises with every version and RC. From
  1.0.0 on (major ≥ 1, so ≥ 1 000 000) it sits above every count-based code
  already uploaded (the highest was under 200, ADR 0030); a 0.x tag would land in
  that old range and can collide, so never tag one. Any other
  build (untagged, ahead of a tag, dirty) keeps `git rev-list --count HEAD`, so
  local builds still install over each other. minor, patch and rcN are capped at
  99 and refused above it, rather than wrapped into a code that is already taken.
  The formula is `MuvissVersion.releaseCode` in `build-logic` (tested by
  `./gradlew -p build-logic test`, a CI step) and `scripts/release/version-code.sh`,
  which the workflows and fastlane call. They must agree; both test suites pin
  the same values.
- `versionName` = the latest reachable tag (`git describe --tags --always
  --dirty`, `v` prefix stripped) when one exists, otherwise
  `0.1.0-dev.<count>+<short-sha>` (and `0.1.0-dev.<count>` if git itself
  isn't available, e.g. a source-only archive).

Release candidates are tagged `vX.Y.Z-rcN` and carry the **final** versionName
`X.Y.Z` — the pattern drops `-rcN` — because production promotes the RC binary
itself (ADR 0025). Nothing is bumped by hand; how versions are chosen and tagged
is §5.

The tags are **annotated**, not lightweight, because `git describe` prefers
annotated tags — and `git describe` is what becomes `versionName`. Version
ordering everywhere uses `sort -V`, so `1.9.0` → `1.10.0` is an upgrade; a
string comparison would call it a downgrade.

Implementation note: the git commands run through Gradle's
`providers.exec {}` (not raw `ProcessBuilder`) — this project runs with
`org.gradle.configuration-cache=true`, and starting an external process
directly at configuration time is incompatible with the configuration
cache.

## 4. Crash reporting (Sentry)

Sentry Kotlin Multiplatform (`io.sentry:sentry-kotlin-multiplatform`,
pinned in `gradle/libs.versions.toml` as `sentryKmp`) is wired behind a
`CrashReporter` seam in `:core:common`
(`core/common/src/commonMain/.../crash/CrashReporter.kt`), following the
same expect/actual convention already used there for `AppDispatchers`:

- **android / iOS / jvm**: `SentryBackend` (the shared `sentryMain` source
  set) calls `Sentry.init`/`captureException` from `sentry-kotlin-multiplatform`.
- **js / wasmJs**: `WebCrashBackend` drives Sentry's browser SDK, loaded from
  Sentry's CDN only when a DSN is configured (#83). The KMP artifact's web
  variants are no-op stubs. Web launch is deferred, and so are its source
  maps (#161).

**The Sentry project.** One org in Sentry's **EU region** (Frankfurt), chosen
at org creation and not changeable afterwards, and **one** project, `muviss`,
for every platform; filter by the SDK's `os`/`platform` tags. One project is
what one baked-in `SENTRY_DSN` gives, and the cost is release health blended
across platforms — which nothing reads, since session tracking is off (#125).
Project settings that the privacy policy relies on: Data Scrubber and default
scrubbers on, **Prevent Storing of IP Addresses on**. Rate limits on individual
DSN keys need a Business plan, so on the free plan Spike Protection is the only
guard against a crash loop eating the quota. The upload credential is an **organization auth token**: it embeds
the region URL, so neither the Gradle plugin nor `sentry-cli` needs
`SENTRY_URL`.

The DSN is baked in via a generated `MuvissBuildConfig` in `:app:shared`,
the same mechanism ADR 0007 describes for the TMDB key: it reads
`SENTRY_DSN` from `local.properties` or the environment, defaulting to `""`.
`CrashReporter.init` with a blank DSN no-ops, so building/running without a DSN (the
default for every contributor and most CI runs) never touches Sentry.

To enable it locally: add `SENTRY_DSN=https://<key>@o<org>.ingest.sentry.io/<project>`
to `local.properties`. In CI: set the `SENTRY_DSN` repository secret (see
`.github/workflows/android-release-build.yml`).

**Where it starts (EPIC 26).** Every host starts reporting *first*, before Koin:
`MuvissApplication.onCreate` (Android), `main()` in the desktop `Main.kt`, and
`IosAppStartup.start` (iOS), all through `MuvissCrashReporting.start` in
`:app:shared`. It used to happen inside a composable, so a process started by
`NewEpisodesWorker`, a Koin graph failure and any pre-first-frame crash were
invisible. `MuvissApp()` still calls `MuvissCrashReporting.ensureStarted()` as a
guard; `CrashReporter.init` is idempotent, so it is a no-op wherever a host
already started, and it starts with reporting *off* if it is ever the first
caller (it cannot know the stored consent). Web has a reporter too (#83): it
loads Sentry's browser SDK from Sentry's CDN rather than
`sentry-kotlin-multiplatform` (which publishes no-op web stubs), and since the
web build has no pre-Koin startup hook to call `MuvissCrashReporting.start`
from, `MuvissApp()`'s `followSettings()` call is the *only* place its consent
is applied, not just a guard — see `docs/PRIVACY.md`.

**Consent.** "Send crash reports" (Settings → Privacy, default on) lives in
`appSettings.crashReportsEnabled` (`10.sqm`). Because reporting has to start
before the graph that opens the database exists, `CrashReportsConsent` reads it
through a short-lived driver of its own, and any failure reads as *on* — the
default. A runtime gate in the SDK's `beforeSend` makes the toggle effective at
once; `MuvissCrashReporting.followSettings()` keeps it in step. The simpler
alternative would be to initialise first and gate afterwards, at the cost of an
opted-out person's crash in that first window being sent.

**`release`, `dist`, `environment`.** `release` is
`<namespace>@<versionName>+<versionCode>` and `dist` the versionCode. The
namespace is per platform (`crashReleaseNamespace`, ADR 0033): Android, desktop
and web report `com.codingpit.muviss`, exactly what the Sentry Gradle plugin
stamps on the R8 mapping it uploads, and they must stay identical or a trace
cannot find its mapping (`MuvissCrashReporting.releaseOf` and
`app/androidApp/build.gradle.kts`); iOS reports `com.codingpit.muviss.ios`, so
its crash gate never reads Play's crashes. `fastlane/sentry_gate.rb`'s
`NAMESPACES` names the same two strings, and a change to either side changes both. `environment` is `SENTRY_ENVIRONMENT`
(env or `local.properties`) if set, otherwise `production` when a release
artefact was requested and `development` otherwise.

**Deobfuscation.** `io.sentry.android.gradle` (`:app:androidApp`) uploads the R8
mapping on release builds when **all three** of `SENTRY_AUTH_TOKEN`,
`SENTRY_ORG` and `SENTRY_PROJECT` are present (env or `local.properties`);
without them it does nothing, so a fresh clone still builds. Nothing here has
been exercised against a real Sentry project yet — see "Sentry test crash" below.

**Scrubbing.** `CrashScrubber` removes `api_key=`, tokens and `Bearer …` from
messages, exceptions and breadcrumbs in `beforeSend`/`beforeBreadcrumb`.

**Version pairing.** `sentryKmp` and `sentryCocoa` move together (see the catalog);
last checked 2026-09-20: 0.27.0 is the latest KMP release and pins Cocoa 8.58.2.

### The Ops Bridge: Sentry and Play reviews to GitHub issues (#300, EPIC 48)

Both connectors share one shape (`fastlane/ops_bridge/`, see its README for how to add a third): a connector polls its source, a sink opens one GitHub issue per new item, an optional reverse mapping acts on a closed issue.

#### Sentry crash to issue (#300, connector #1)

`.github/workflows/sentry-issues.yml` opens one GitHub issue per new **unhandled
production** crash and resolves the crash in Sentry when that issue is closed.
Sentry's own GitHub integration can do the first half automatically only on its
Business plan; this runs on any plan, with the token the Play ramp's crash gate
(`fastlane/sentry_gate.rb`) already uses.

- **Hourly** (and by hand, dry by default): lists `is:unresolved error.unhandled:true
  environment:production`, narrowed to `SENTRY_PROJECT` when it is set, and opens an issue
  labelled `crash` + `from-sentry` for each one that has none. The body is counts and names
  only (short id, culprit, first and last release, events, users, link) and ends in
  `<!-- sentry:MUVISS-N -->`; never a raw event.
- **Idempotent**: the marker is looked up in the existing `from-sentry` issues (open and
  closed, not the search index, which lags) before anything is created, so re-running changes
  nothing. A crash that comes back after its issue was closed does **not** reopen it (#327).
- **Closing a `from-sentry` issue** resolves the Sentry issue *in the next release*
  (`PUT /organizations/{org}/issues/?id=…`, the id from `/shortids/{short_id}/`). Reopening
  does not unresolve it.
- **Token scope: `event:write` is new.** The gate only reads (`event:read`, `org:read`);
  resolving needs `event:write` on the same `SENTRY_AUTH_TOKEN`. Without it the hourly run
  still works and only the close-to-resolve step fails (HTTP 403 in the run log).
- **Secrets**: `SENTRY_AUTH_TOKEN`, `SENTRY_ORG`, optionally `SENTRY_PROJECT` (all already
  set for the mapping upload and the gate). With no token the workflow does nothing.
- **Try it**: Actions > "Sentry issues" > Run workflow (dry run is on by default and prints
  what it would open). Tests: `ruby fastlane/test/sentry_connector_test.rb` and `ops_bridge_test.rb`, run by `pr-guards`.
  Nothing here has been run against a live Sentry project (#328).

#### Play reviews to issue (EPIC 48, connector #2)

`.github/workflows/play-reviews.yml` opens one issue per Google Play review of **3 stars or
fewer**, labelled `from-play-review` + `user-feedback`.

- **Daily, not weekly**: the Reviews API returns only reviews **with text**, created or edited
  in the **last week**, and only for production versions. A gap longer than a week loses reviews
  for good (the console's CSV export is the only way back). One issue per review id, so an
  edited review does not open a second.
- **The reviewer's name is never copied** (the repository goes public). The body has the rating,
  app version, Android API level, device model, language and date, and the review text in a code
  fence, so an `@name` inside it cannot ping anyone. Reviews of 4 and 5 stars are praise, not work.
- **No reverse mapping**: closing the issue changes nothing in Play.
- **Owner step: a Play Console grant.** The service account behind `PLAY_SERVICE_ACCOUNT_JSON`
  (the upload account) needs the permission **Reply to reviews** (Users and permissions >
  the account > App permissions). Play has no read-only reviews permission, so the account
  *could* reply; nothing here does, and the key lives only in Actions. Without the grant the run
  fails with HTTP 403 and opens nothing. It also needs the Google Play Android Developer API
  enabled in the account's Cloud project (the upload already needs that).
- Stdlib only: the OAuth token is a signed JWT exchanged at Google (`PlayReviews#access_token`),
  no gems. Try it: Actions > "Play reviews" > Run workflow (dry run is on by default).
  Tests: `ruby fastlane/test/play_reviews_test.rb`. Never run against the live API.

#### Feature requests and bug reports to issue (#448, #449, connectors #3 and #4)

`.github/workflows/feature-requests.yml` opens one issue per completed submission of the in-app
**Request a feature** form (section 13), labelled `from-feature-request` + `user-feedback`, and of
the **Report a problem** form, labelled `from-bug-report` + `bug` + `user-feedback`.

- **Hourly** (and by hand, dry by default). Polling rather than a Tally webhook: Tally's webhook
  sends its own fixed JSON and cannot call GitHub's dispatch API, so it would need a hosted
  receiver, which ADR 0028 rules out. Tally keeps every submission, so nothing is lost between runs.
- **The respondent's email is never copied** (the repository goes public); the issue only says
  whether one was left, and the owner replies from the Tally dashboard. The text goes in a code
  fence, so an `@name` cannot ping anyone. The hidden fields (`version`, `platform`, `os`,
  `locale`) are shown, sanitised, since a query parameter is anything someone types.
- **Capped**: an empty submission is skipped and one run opens at most 10 issues, oldest first, so a
  flood of junk costs an hour at a time. One issue per submission id; re-running opens nothing.
- **No reverse mapping.** Closing the issue changes nothing in Tally.
- **Secrets**: `TALLY_API_KEY` (Tally > Settings > API keys) and `TALLY_FEATURE_REQUEST_FORM_ID`
  (the id `scripts/feedback/tally-form.sh create` printed); `TALLY_BUG_REPORT_FORM_ID` for the Bug
  Report form. With the key or a form's id unset, that form's step does nothing. `scripts/release/owner-setup.sh` asks for both. Scheduled workflows only run from the
  default branch, so nothing happens until this reaches `main`.
- **Try it**: `DRY_RUN=1 TALLY_API_KEY=… TALLY_FEATURE_REQUEST_FORM_ID=… GITHUB_REPOSITORY=… GITHUB_TOKEN=… ruby fastlane/ops_bridge.rb sync tally-feature-requests`,
  or Actions > "Feature requests" > Run workflow (dry run is on by default). Tests:
  `ruby fastlane/test/tally_feature_requests_test.rb`, run by `pr-guards`.

## 5. Branches, the release train and CI (ADR 0025)

### The flow

```
feature/x ──squash──► develop ──(Monday train)──► release/1.3.0 ── every push: tag vX.Y.Z-rcN
                         ▲                          │             → signed AAB → Play INTERNAL
                         │                          │
                         │        Sunday, week 0    ▼
                         │        gates pass ──► Play BETA (open testing), same RC binary
                         │                          │
                         │        Sunday, week 1    ▼
                         │        gates pass ──► PR release/1.3.0 → main, merged as the App
                         │                          │  then beta → production 20% by the same run
                         │                          │  (release.yml: tag v1.3.0)
                         │                          │  ramp 50% day 2, 100% day 4 (play-rollout-ramp.yml)
                         └── back-merge PR ◄────────┘  GitHub Release: the RC's AAB + DMG/DEB/MSI
hotfix/1.3.1 is cut from main (start-release.sh 1.3.1 --hotfix) and goes the same way.
```

Every version walks three stages (ADR 0030). See **Staged releases** below for
the schedule, the gates and where a fix goes.

| workflow | trigger | does |
|---|---|---|
| `ci.yml` | PRs into `develop`/`main`; pushes to `develop`, `main`, `release/**`, `hotfix/**` | build, tests, desktop, iOS, build-logic tests (versionCode) |
| `pr-guards.yml` | PRs into `develop`/`main`, incl. retitle/relabel | `main-source-guard`, `release-notes-guard`, `landing-guard`, release tooling tests |
| `release-train.yml` | nightly 23:00 UTC; acts on `RELEASE_TRAIN_DAY` (default `1`, Monday); dispatch | cut `release/<next>` if `develop` has new commits and no version is still in internal; create its milestone and "Release X.Y.Z" issue |
| `release-rc.yml` | push to `release/**`, `hotfix/**` | tag `vX.Y.Z-rcN`, build (`android-release-build.yml`), Play internal, pre-release |
| `release-promote.yml` | Sunday 10:00 UTC; dispatch (`dry_run` defaults on) | every gate, then internal → beta, or merge beta's release PR into `main` as the App and promote beta → production (20%) |
| `release.yml` | release/hotfix PR merged into `main`; dispatch | tag `vX.Y.Z`, delete the branch, on a manual merge promote → production (20%), desktop installers, GitHub Release, back-merge PR |
| `release-forward.yml` | a PR merged into `release/*` or `hotfix/*` | open the fix again into every newer `release/*` and `develop` |
| `release-milestone.yml` | a PR opened into `release/*` or `hotfix/*` | milestone `X.Y.Z` on it |
| `play-rollout-ramp.yml` | daily 08:00 UTC; dispatch | raise the production fraction along the ramp; close the release issue at 100% |
| `play-promote.yml` | dispatch | `rollout`, `halt`, `resume` (and `promote`, by hand) |

### Staged releases (ADR 0030)

| When | Stage | What happens |
|---|---|---|
| Monday 23:00 UTC (week 0) | internal | The train cuts `release/X.Y.Z`; the RC goes to the internal track. |
| Sunday after (week 0) | open beta | The same RC binary is promoted from internal to the `beta` track (open testing). |
| Sunday a week later (week 1) | production | `release/X.Y.Z` merges into `main` as the release App (`release.yml` tags it), and the same run promotes the beta build to production at 20%. The daily ramp takes it to 100%. |

- **Up to three versions in flight, one per stage.** The train holds only while
  a version is still in internal, because a new RC would replace it on that one
  track. A version in beta, or ramping in production, does not stop a cut.
- **Production is fully automatic** on the Sunday path: the `production`
  environment's approval is not asked (the gates and the crash gate that holds
  the ramp stand in for it). A manual merge into `main` still asks for it.
- **Production leaves beta before the next version arrives.** The Sunday step
  runs production before beta and promotes production itself, so a newer RC
  never takes the beta track while an older one is still being read from it,
  for every version the release issues track (a build put on beta by hand in
  Play Console is invisible to it; see the gates below).
  If Play refuses the promotion after the merge, the version stays on beta
  with `merged_on` in its marker, and the next run (or a dispatch) retries
  only the promotion, never the merge; the newer beta waits for it.
- **Every step is gated.** A held version says why on its "Release X.Y.Z" issue
  and is retried the next Sunday, or by a dispatch of `release-promote.yml`
  (`dry_run` defaults on: it prints every decision and changes nothing). The gates:
  - an open issue or PR labelled `release-blocker` in the version's milestone;
  - an open PR into `release/X.Y.Z` (a fix in flight);
  - the newest RC is not the branch head (a commit landed after it was built),
    or that RC's `release-rc.yml` run did not succeed;
  - CI on the branch head is not green;
  - the store notes still carry `DRAFT:` (or are missing), checked at the beta
    step too, since testers see them;
  - the Sentry crash gate (`fastlane/sentry_gate.rb`) finds an unhandled issue
    first seen in that build;
  - another version is still on the beta track (one per stage). This is read from
    the release issues' markers, not from Play: a version promoted to beta by hand,
    or whose marker is missing or stale, is not seen here. Production also checks
    Play itself before merging, that beta's newest build is the RC (#349);
  - for production: the version has not had its full week on beta. A held beta
    step pushes production back with it.
- **Brakes.** `gh workflow run play-promote.yml -f action=halt` stops a production
  rollout (the ramp leaves a halted release alone until `resume`). A
  `release-blocker` issue or PR in the milestone holds the next Sunday step. The
  Sunday step does not read Play's halt state, so a halted release can still be
  moved to beta or merged into `main`: halt it, and put a `release-blocker` on
  its milestone, to stop both.
- **Where a fix goes.** A fix for a version in flight is a PR into
  `release/X.Y.Z` (or `hotfix/X.Y.Z`), labelled into that milestone
  automatically. When it merges, `release-forward.yml` opens it again, as the
  release App, into every newer open `release/*` branch and into `develop`, each
  linking the original. A forward PR that conflicts is still opened, empty, and
  stays open for a person; a forward PR into a release branch holds that
  version's promotion until it merges. Each push to a release branch is a new RC.
- **Owner prerequisites.** The `beta` (open testing) track must exist in Play
  Console with a tester opt-in URL. The release App needs **Contents**,
  **Pull requests** and **Issues** write, and **Actions** read (the RC run check);
  it must also be a bypass actor on the `main` ruleset, or the Sunday merge is
  refused (the step then holds, with that reason).

### iOS in the train

iOS walks the same three stages as Play, one week apart, on the same RC binary (ADR 0033).
Its table, rules, lanes and the owner runbook are in section 9 ("9.4 The staged train for iOS").

### Cutting a release

The train does it on Mondays. By hand (the first release, or an off-cycle one):

```bash
scripts/release/start-release.sh 1.0.0            # release/1.0.0 from origin/develop
scripts/release/start-release.sh 1.0.1 --hotfix   # hotfix/1.0.1 from origin/main
scripts/release/start-release.sh 1.0.0 --dry-run  # checks only
scripts/release/next-version.sh                   # what the train would cut
```

It refuses when the tree is dirty, the branch or tag exists, the version is not
newer than the last release, the Play listing is over a limit, or (releases
only) a `release/*` branch is open. The train applies ADR 0030's stagger rule
itself and passes `--stagger-checked`; a hand cut refuses while any release
branch is open, unless you pass that flag after checking that none is still in
internal. The branch starts with one commit:
draft release notes for every listing locale, seeded from the `feat`/`fix`
subjects since the last release, first line `DRAFT: …`, and this version's
section at the top of `CHANGELOG.md` (every `feat`/`fix` with its scope,
uncapped). The changelog is generated, not edited: a fix merged into the
release branch later is in the RC but not in that section.

Then:

1. Install the RC from Play's internal track and run §8.
2. Fix anything on the release branch (PRs into `release/x.y.z`). Each push is a new RC.
3. Rewrite the notes for users in every locale and delete the `DRAFT:` line —
   `release-notes-guard` fails the PR into `main` until you do.
4. Nothing, on the scheduled path: the Sunday step after the beta opens the PR
   `release/x.y.z → main` and merges it as the App (see **Staged releases**). To
   merge by hand instead, open that PR and **merge with a merge commit** — or
   Actions > **Ship release** > Run workflow (`action: ship`), which opens it,
   waits for the approval and checks, and merges it that way.
5. On a manual merge, approve the `production` deployment in the run. The ramp
   takes it from there; halt with `gh workflow run play-promote.yml -f action=halt`.
6. Merge the back-merge PR into `develop` **with a merge commit** (it is labelled
   `no-landing`; resolve conflicts on its `back-merge/vX.Y.Z` branch) — or run
   **Ship release** with `action: back-merge` (pass the version as `branch` when
   more than one back-merge PR is open).

Production gets the build on the beta track: the RC that the Sunday step promoted
to beta, which its gates required to be the branch head. If the branch has moved
since, the gates hold the Sunday step until a new candidate is built. A manual
merge is not gated that way, so `release.yml` warns and still promotes the RC.
It promotes from beta, or from internal when the approved RC never went to beta
(a hotfix, or a version shipped ahead of its Sunday). A release with no RC fails
at the tag step.

### Branch protection

Rulesets (applied by `scripts/release/owner-setup.sh`), admins may bypass:

- **main**: PR, 1 approval, merge commits only, checks `build`, `desktop`, `ios`,
  `Release tooling tests`, `PRs into main come from release/* or hotfix/*`,
  `Release notes are final`. The `muviss-release` App is a **bypass actor** here:
  the Sunday step merges the release PR as the App, without a CodeRabbit approval
  (ADR 0030). Its gates, not the approval, are what stand in for it.
- **develop**: PR, 1 approval, squash or merge, checks `build`, `desktop`, `ios`,
  `Release tooling tests`, `Features reach the landing`.
- **release-tags** (`refs/tags/v*`): only admins and the `muviss-release` App
  create, move or delete them.

The approval is **CodeRabbit**'s (`.coderabbit.yaml`, request-changes workflow):
it approves once every comment it made is resolved. If it is unavailable, an
admin merges with `gh pr merge --admin` and says so in the PR. Skipped jobs (for
example `ios` on a docs-only PR) count as passing.

### The release App

`muviss-release` is a private GitHub App (secrets `RELEASE_APP_ID`,
`RELEASE_APP_PRIVATE_KEY`; variable `RELEASE_APP_ID`). The train, the RC tags,
the back-merge PR, the Sunday promotion, and the forward PRs use its token
because anything done with `GITHUB_TOKEN` starts no workflow: a train branch
would never build an RC, and a PR opened with it would never get CI. Without
the App, the RC and release jobs fall back to `GITHUB_TOKEN` (and the tag
ruleset will refuse them), and the train fails.

Its permissions (ADR 0030): **Contents** write (train branches, forward
branches, RC tags), **Pull requests** write (back-merge, forward and release
PRs, merging), **Issues** write (the release issues, milestones, and the
blocked-train issue), and **Actions** read (the Sunday step checks the RC's run).
It is a bypass actor on the `main` ruleset (see **Branch protection**).

### Shared build

`android-release-build.yml` (`workflow_call`) checks out full history
(`fetch-depth: 0`, needed for versionCode/versionName), decodes
`KEYSTORE_BASE64`, runs `:app:androidApp:bundleRelease` with the signing,
TMDB and Sentry secrets, uploads the AAB as an artifact and, for RCs, runs
`fastlane android internal`. `release.yml`'s dispatch path calls it without the
upload to exercise the build.

### Publishing to Google Play (ADR 0024, EPIC 34 / #77)

Wired in `android-release-build.yml` (`fastlane android internal`, from
`release-rc.yml`), `release-promote.yml` (`android beta`, from the Sunday step),
`release.yml` (`android promote`), `play-rollout-ramp.yml` (`android ramp`) and
`play-promote.yml` (`promote`, `rollout`, `halt`, `resume`). The ramp policy is
`fastlane/play_rollout.rb`, tested by
`fastlane/test/play_rollout_test.rb`. The Fastfile is
`fastlane/Fastfile`; run any lane locally with `bundle exec fastlane <lane>`
(Ruby 3.1+, `.ruby-version` pins 3.3 — macOS's system Ruby 2.6 is too old).

- **First uploads are drafts.** Play refuses any release status but `draft`
  until the app's first production release exists, so the workflows default
  `PLAY_RELEASE_STATUS` to `draft`; set the repository variable to `completed`
  once production is live. The very first AAB must be uploaded by hand in the
  Console anyway, because the API cannot create the app — that upload is also
  where Play App Signing enrolment happens.
- **No secret, no upload.** Without `PLAY_SERVICE_ACCOUNT_JSON` every Play step
  logs a notice and passes; dispatch runs never upload.
- **Release notes are mandatory once listing metadata exists**, one file per
  listing locale at `fastlane/release-notes/vX.Y.Z/<locale>.txt` (max 500
  characters), written on the release branch; every RC of that version uses
  them. They are keyed by version, not by versionCode, because the versionCode
  comes from the RC tag, which exists only once the candidate is built. The lane copies them
  to supply's `changelogs/<versionCode>.txt` (generated, gitignored).
- **The first production release is a draft too**: with
  `PLAY_RELEASE_STATUS=draft`, `promote` creates a draft production release
  with no fraction, which you send for review in the Console. After it is
  approved, `gh variable set PLAY_RELEASE_STATUS --body completed`.
- **A release build refuses to guess its versionCode**: without git, or in a
  shallow clone, `:app:androidApp`'s release tasks fail at configuration
  instead of shipping `versionCode = 1`.

The decisions behind it:

- **fastlane `supply`**, not gradle-play-publisher: it consumes the built `.aab`,
  so AGP/Gradle upgrades cannot break publishing. Ruby is pinned by a
  `Gemfile`/`Gemfile.lock` at the repo root.
- **An RC uploads to the internal track only, and the track moves it on.** The
  Sunday step after the train's cut promotes it internal → `beta` (Play's open
  testing track, `fastlane android beta`), and the Sunday a week later merges the
  release into `main` and promotes the beta build to production at 20%
  (`fastlane android promote`, which checks the beta track's latest is the
  approved RC). On a manual merge `release.yml` promotes instead, behind the
  `production` environment's approval (ADR 0030; it was the whole of ADR 0025's
  gate), and may take an RC that never went to beta from internal.
- **Listing copy and screenshots live in `fastlane/metadata/android/<locale>/`**
  and are the source of truth (`docs/store/LISTING.md` explains them).
- Secret: `PLAY_SERVICE_ACCOUNT_JSON` (a Play Console service account with
  release permission on `com.codingpit.muviss`).
- The Play account is a personal one created before November 2023, so the
  12-tester / 14-day closed-test requirement does not apply.
- v1 is **local-only** (ADR 0024): sync stays compiled out, as the comment in
  `android-release-build.yml` already insists.

### The GitHub Release (issue #45)

`release.yml`'s `github-release` job attaches the RC's own AAB (downloaded from
its `vX.Y.Z-rcN` pre-release, never rebuilt) and all three desktop installers to
a real GitHub Release for `vX.Y.Z`. RCs get pre-releases of their own from
`release-rc.yml`, which also keeps each candidate's AAB past the 90-day
workflow-artifact limit.

- **Release-only.** A `workflow_dispatch` run builds the AAB and installers as
  workflow artifacts and creates no tag and no Release.
- **It uses `gh`**, not a third-party release action: it ships on the runner and
  adds no supply-chain trust to a repo that vets its own app dependencies
  against a licence allow-list.
- **A failed installer leg blocks the Release.** `desktop-release` is
  `fail-fast: false`, so a broken MSI still lets the DMG and DEB finish, but
  `github-release` needs every leg green, so the result is no Release rather
  than a partial one.

The notes say plainly that the desktop installers are unsigned (#39, #179),
that the `.aab` is not directly installable, and that sync is compiled out of
release builds (ADR 0018).

## 6. OSS attribution / license report

The epic asked for an OSS-attribution screen fed by a license-report
Gradle plugin. `com.github.jk1.dependency-license-report` (3.1.4, the
current release) was tried first and dropped: its report task calls
`Task.project` at execution time and isn't
configuration-cache-compatible — `generateLicenseReport` failed with
"cannot serialize object of type `DefaultProject`" against this project's
`org.gradle.configuration-cache=true` setting.

**EPIC 8 tried `app.cash.licensee` next and it works.** Applied to
`:app:androidApp` only (see that module's `build.gradle.kts`):
`./gradlew :app:androidApp:licenseeAndroidDebug` and
`licenseeAndroidRelease` both run clean and store a configuration-cache
entry — no `DefaultProject`-style failure. The plugin's own `check` task
integration means `./gradlew check` runs it too. It fails the build if any
dependency resolves to a license outside the `allow(...)`/`allowUrl(...)`
list in that build file; bump the list deliberately (not by blanket-allowing
"unknown") if a legitimately-new license shows up.

What it does *not* do: feed the Settings screen live. `licenseeAndroidRelease`
only analyzes the **Android** variant's resolved dependency graph (Android is
this app's primary target, see CLAUDE.md) and writes its
`build/reports/licensee/androidRelease/artifacts.json` into a module
(`:app:androidApp`) nothing else depends on — feeding that file's contents
into `feature/settings/ui`'s Licenses screen without an app→feature dependency
inversion would need either a cross-module generated-resource pipeline or
Compose Multiplatform's raw-resource mechanism, either well beyond this
epic's timebox. Instead, `feature/settings/domain/.../OssLicenses.kt` is a
**generated-once, checked-in** `List<OssLicense>` produced from that same
`artifacts.json` (253 entries as of this pass). Regenerate it by re-running
`:app:androidApp:licenseeAndroidRelease` and re-deriving the list from the
refreshed `artifacts.json` whenever dependencies change meaningfully — it
will silently go stale otherwise, unlike the build-time gate above, which
never can.

`docs/PRIVACY.md` and the TMDB attribution constant in
`feature/settings/domain/.../TmdbAttribution.kt` remain the source of truth
for attribution text; both are now surfaced on the Settings "About" screen.

## 7. Desktop installers (EPIC 12 / issue #14)

`:app:desktopApp` produces native installers via Compose Multiplatform's
`compose.desktop.application.nativeDistributions` DSL (a wrapper over the
JDK's own `jpackage`) — see `app/desktopApp/build.gradle.kts`.

### Build commands

```bash
./gradlew :app:desktopApp:packageDmg   # macOS — only runs on macOS
./gradlew :app:desktopApp:packageMsi   # Windows — only runs on Windows
./gradlew :app:desktopApp:packageDeb   # Linux — only runs on Linux
./gradlew :app:desktopApp:packageDistributionForCurrentOS   # whichever format matches the host OS
```

Each is cross-compile-incapable by construction — `jpackage` calls into the
host OS's own native packaging tool under the hood (`hdiutil`/`pkgbuild` for
DMG, WiX for MSI, `dpkg-deb` for DEB) — which is why CI needs one runner per
format (see below) rather than a single job producing all three.

Artifacts land under `app/desktopApp/build/compose/binaries/main/<format>/`,
e.g. `.../dmg/Muviss-1.0.12.dmg`. The unpacked `.app`/image (pre-installer)
is at `.../app/Muviss.app` (macOS) — useful for a faster launch check than
mounting the DMG.

### Versioning

`packageVersion` is derived from git the same way `versionCode`/`versionName`
are (see item 3), duplicated into `app/desktopApp/build.gradle.kts` rather
than shared, consistent with the existing per-module convention — but mapped
differently, because jpackage's installer backends are far stricter about
version *syntax* than an Android `versionName` string:

- **DMG** (`pkgbuild`): parses as up to 3 dot-separated integers, and the
  first one can't be `0` — `packageDmg` fails outright with
  `Invalid Package-Version` on anything like `0.1.0-dev.12` (this repo's
  actual `versionName` today, since there are no tags yet).
- **MSI** (WiX): up to 4 dot-separated integers.
- **DEB**: Debian policy version syntax — far more permissive than the other
  two, no reason to diverge though.

One scheme satisfies all three: a real, exact `vMAJOR.MINOR.PATCH` tag (with
`MAJOR >= 1`) is used verbatim; every untagged/dev build — the norm today —
falls back to `1.0.<commitCount>`. That's monotonic (commit count only ever
grows) and always clears every format's "major can't be 0" rule, with no
dev-suffix to strip since it never had one. Once real `v1.x.x`+ tags exist
this stops mattering, but nothing needs to change when that day comes.

### jlink modules

`jlink` keeps only the JDK modules reachable from the roots declared in
`app/desktopApp/build.gradle.kts`'s `jlinkModules`; anything else is stripped,
and a module that is genuinely needed and simply absent surfaces as a runtime
`NoClassDefFoundError`/`module not found` rather than a build failure.

**The list is verified, not remembered** (issue #43). `verifyJlinkModules`
re-derives what the app actually needs and fails if the declaration cannot
supply it. It is wired into `:app:desktopApp:check` and runs as its own step
in CI's `desktop` job, so you should never need to run it by hand — but it is
one command if you want to:

```bash
./gradlew :app:desktopApp:verifyJlinkModules
cat app/desktopApp/build/reports/jlink-modules.txt
```

It asks two questions, because the answer is not simply "is every required
module declared":

- `jdeps --print-module-deps` over the uber jar says what is **required**.
- `java --limit-modules <roots> --list-modules` says what those roots
  **resolve to**, which is what jlink will actually put in the image.

A missing module fails the build. Two other findings are reported and do not:

- **Declared but never named by jdeps.** jdeps sees static references only, so
  a module reached by reflection or JNI looks exactly like dead weight.
  Removing one on that evidence alone would be a mistake.
- **Required but resolving only transitively.** Nothing is broken while it
  does, but it is load-bearing by accident and disappears the moment whichever
  root happens to require it is dropped.

That second case was not hypothetical: `java.prefs` — `java.util.prefs`, where
`DesktopWindowState.kt` stores the window geometry — had never been declared,
and survived only because `java.desktop requires java.prefs`. The check found
it on its first run and it is now a root.

Two roots are worth knowing by name, because nothing in application code names
the module and so nothing would remind you:

- `java.sql` is what `:core:database`'s `sqlite-jdbc` driver
  (`DatabaseFactory.jvm.kt`) needs.
- `jdk.httpserver` (EPIC 23) is `com.sun.net.httpserver`, which
  `LoopbackRedirectServer` uses for desktop's OAuth redirect (ADR 0017).

The other CI steps narrow this but never closed it, which is why the check
exists: `packageDistributionForCurrentOS` runs jlink, and jlink fails on a
module *name* it cannot resolve, so a typo was caught — but a needed-and-absent
module was not. The headless smoke launch exercises the packaged runtime image
along the startup path only, and `jdk.httpserver` is not touched until someone
clicks sign in.

### App icon

`app/desktopApp/icons/{icon.icns,icon.ico,icon.png}` are the real app icon,
rasterised from the SVG masters in `docs/design/icon-src/` (#163), like every
other platform's. Regenerate them from there; the build depends only on
their presence/format (`.icns` for `macOS { iconFile }`, `.ico` for
`windows { iconFile }`, `.png` for `linux { iconFile }`).

### Sign-in ports

Desktop's OAuth redirect is a loopback HTTP server on 53682, 53683 or 53684 —
whichever is free — rather than the `muviss://` scheme Android and iOS use
(**ADR 0017**). Every one of those ports must appear in `supabase/config.toml`'s
`additional_redirect_urls` and be applied with `supabase config push`;
`LoopbackRedirectServer.PORTS` is the same list. The listener is opened when a
sign-in starts and closed on the code, an error, a five-minute timeout, or a
second attempt — nothing listens while the app is idle.

Where a drift shows up is worth knowing, because it is not where you would look:
GoTrue's `/authorize` accepts **any** `redirect_to` and redirects to the provider
regardless, so the flow starts normally. The allow-list is enforced when GoTrue
redirects *back*, after the user has authorized, and presents as the browser
landing on `site_url` with "cannot connect to the server".

### Window size/position persistence

`app/desktopApp/src/main/kotlin/com/codingpit/muviss/DesktopWindowState.kt`
persists the window's size/position across restarts via
`java.util.prefs.Preferences` (per-user, JVM-only, schema-less) rather than
the shared `appSettings` SQLDelight table. That table is `commonMain` schema
shared by every target (ADR 0004); adding window-geometry columns nobody but
desktop reads or writes would need a `.sqm` migration + updated verification
fixture (ADR 0008) for a value that only exists as a concept on one of five
targets — Android/iOS get equivalent behavior for free from their own window
managers, and web has no native window to persist at all. Verified locally:
launching the packaged `.app`, letting it settle, and inspecting
`~/Library/Preferences/com.codingpit.muviss.plist` showed the expected
`width`/`height`/`x`/`y` keys under `desktop/window/`.

### Verifying locally

Only the current host OS's format can be exercised:

```bash
./gradlew :app:desktopApp:packageDmg          # macOS
open app/desktopApp/build/compose/binaries/main/app/Muviss.app   # launch check
```

`packageMsi`/`packageDeb` can't run on macOS (or vice versa) — CI is what
exercises them (see below).

Launching the packaged app is worth doing rather than trusting `./gradlew run`:
`run` executes against your full JDK and says nothing about the stripped jlink
runtime image users actually get. `MUVISS_EXIT_AFTER_FIRST_FRAME=1` makes either
one quit as soon as it has drawn, which is what CI's smoke step uses — an
environment variable and not a `-D`, because Gradle does not forward system
properties to the JVM a JavaExec forks.

### CI

`.github/workflows/release.yml`'s `desktop-release` job runs a 3-entry OS
matrix: `ubuntu-24.04` → `packageDeb`, `macos-15` → `packageDmg`,
`windows-2025` → `packageMsi`. Each format can only be produced on its native
OS (jpackage delegates to the OS's own packaging tool), which is why this is a
matrix of jobs rather than one job running three tasks, and `fail-fast: false`
keeps one broken leg from taking the other two down.

Windows/MSI used to be left out, on the grounds that it was the one leg nobody
here could verify locally before merging. EPIC 23 put it in: nobody can verify
it locally *either way*, and a CI run is better evidence than a reading of the
config. WiX ships preinstalled on `windows-2025` per `actions/runner-images`.
What CI still cannot say is whether the resulting `.msi` installs and launches —
that needs a Windows machine, and is issue #44.

The job runs when a release branch merges into `main` (built from the new
`vX.Y.Z` tag) **or** on `workflow_dispatch`. Dispatch exists because until
EPIC 23 this job had never executed once, so every packaging path was verified
by reading it. `packageVersion` falls back to `1.0.<commitCount>` when
untagged, so a dispatch run exercises the same code a release does.

Per-PR coverage is separate and lighter: `ci.yml` runs
`packageDistributionForCurrentOS` on Ubuntu (so the DEB path and jlink run on
every change), smoke-launches the packaged Linux binary under Xvfb, and runs
`packageDmg` on the macOS runner the iOS job already pays for.

Installers upload as workflow artifacts (`muviss-desktop-<os>-<tag>`) and, on a
release, are attached to the GitHub Release (see §5).

#### Runner images (#481)

Every `runs-on:` names a specific image (`ubuntu-24.04`, `macos-15`,
`windows-2025`), never `-latest`: GitHub moved `ubuntu-latest` to Ubuntu 26 on
its own date, and a migration can break the Xvfb smoke launch, `verifyJlinkModules`
or the iOS Kotlin/Native build on an unrelated PR. `pr-guards.yml`'s "Runner
images are pinned" step (`scripts/ci/check-runner-pins.rb`) fails a floating
label. Dependabot does not bump images, so the owner does, on a branch: change
the label, let `ci.yml` run (it covers Linux, macOS and the packaged-binary
smoke launch; `release.yml`'s Windows leg runs by `workflow_dispatch`), and merge
only when it is green. GitHub announces a retiring image well ahead; watch
`actions/runner-images` releases.

### Signing / notarization (manual follow-up, not attempted)

Every installer produced above is **unsigned**:

- **macOS**: no Developer ID signing or notarization. Gatekeeper will block
  the DMG/`.app` on another machine with "cannot be opened because it is
  from an unidentified developer" until the user right-click → Open's past
  it once. Fixing this needs an active Apple Developer Program membership, a
  Developer ID Application certificate, and `notarytool` wired into the
  build/CI (secrets for the Apple ID/team ID/app-specific password) — real
  money and an enrolled account, out of scope for this epic.
- **Windows**: no Authenticode signing. SmartScreen will warn on first run.
  Needs a code-signing certificate (EV or OV) from a CA — also real money,
  also out of scope here.
- **Linux**: DEB packages are conventionally unsigned for direct
  distribution outside an APT repository (which Muviss isn't published to);
  nothing missing here relative to how most non-repo `.deb`s ship.

None of this blocks producing working installers — it only affects the
first-run trust prompt a user sees. Revisit if/when Muviss gets a real
distribution channel beyond "download the file from a GitHub artifact."

## 8. Manual smoke test before a real release

Automated checks (`spotlessCheck`, `detekt`, unit tests, `assembleRelease`)
catch regressions but not "does it actually work minified on a device."
Before tagging a real release:

1. Install the release build on a device/emulator:
   `./gradlew :app:androidApp:installRelease`.
2. Search for a title, open its detail screen (movie and a TV show with
   seasons), confirm posters load.
3. Add something to the collection, tick an episode, confirm the status
   derivation still works under R8.
4. If Sentry is configured, force a test crash and confirm it shows up in
   the Sentry project within a few minutes.

## 9. iOS (TestFlight / App Store) — EPIC 35 / issue #78, EPIC 11

`app/iosApp` is a thin SwiftUI host embedding `:app:shared`'s Compose UI. Everything on the
repo side is built and checked in (ADR 0033; PRs #426, #427 and #432). What is left is the Apple
account, which only the owner can create. This section is that runbook, followed by how the
release train treats iOS once it is set up.

Status on 2026-10-17: no Apple account exists yet. Enrolment is planned for 2026-10-18.
Nothing in this section has been run against a live Apple account. Where Apple's own pages
could not be confirmed, the step says **verify in UI**.

### 9.1 Ordered checklist for the owner

Who is in brackets: **Owner** does it by hand, **CI** does it on a tag or a schedule, and
**wizard** means `scripts/release/apple-setup.sh` does it for you.

1. Enrol as an **Individual** (O1). **Owner**
2. Copy the Team ID (O2). **Owner**, then the wizard stores `APPLE_TEAM_ID`.
3. Register the App Group and both App IDs (O3, O4). **Owner**
4. Build and run on your own iPhone with your team (O5). **Owner**, and the airplane-mode widget test (device checklist, 9.8).
5. Create the App Store Connect app record (O6). **Owner**
6. Create the App Store Connect API key with the **Admin** role (O7). **Owner**, then the wizard stores three secrets.
7. Sentry token, org and project (O8). **Owner**, then the wizard stores three secrets.
8. TestFlight internal and external groups, the public link, Beta App Review information (O10). **Owner**, then the wizard stores two variables.
9. App Privacy, age rating, price and availability, EU DSA trader answer, export compliance (O12 to O16). **Owner**
10. Replace the four review-contact placeholders, decide the support URL (O11, O17). **Owner**
11. Run `scripts/release/apple-setup.sh --verify` (9.3). **Owner**, read-only.
12. Cut the first release candidate (O18). **Owner** cuts it, **CI** builds and uploads it.
13. Work through the first-live-run checklist (9.6) and record each answer on the release issue. **Owner**

### 9.2 The runbook, step by step

Each step says what it yields and where the value goes. "Verify" is how you know it worked.

**O1. Enrol in the Apple Developer Program as an Individual.** Owner.
- Open <https://developer.apple.com/programs/enroll/>. Sign in with an Apple Account that has two-factor authentication on. Choose **Individual / Sole Proprietor** (verify in UI: the label has changed before). Enter your legal name and address; the name must match your government ID. Pay 99 USD a year.
- Apple checks your identity. The page for it says "usually within 48 hours" (verify in UI); plan for a day or two.
- Yields: an active membership.
- Verify: the Apple confirmation email arrives, and <https://developer.apple.com/account> opens with a Membership section.

**O2. Copy the Team ID.** Owner, and the wizard stores it.
- On <https://developer.apple.com/account>, open Membership details (verify in UI). Copy the 10-character **Team ID**.
- Yields: `APPLE_TEAM_ID`. Goes to the **GitHub variable** `APPLE_TEAM_ID` (the wizard sets it, or `gh variable set APPLE_TEAM_ID --body <id>`). For local device builds it also goes to `TEAM_ID=` in `app/iosApp/Configuration/Config.xcconfig`. **Do not commit that line.** The repo's copy stays blank.
- Verify: `gh variable list` shows `APPLE_TEAM_ID`.

**O3. Register the App Group.** Owner.
- Open <https://developer.apple.com/account/resources/identifiers/list/applicationGroup>. Click **+**, choose **App Groups**, Continue. Description `Muviss`, Identifier `group.com.codingpit.muviss`, Register (verify in UI).
- Yields: the group. The widget reads the app's database through it (ADR 0016). The identifier must match, character for character, the two `.entitlements` files, `MUVISS_APP_GROUP` in `core/database/src/iosMain/.../DatabaseFactory.ios.kt`, and `UserDefaults(suiteName:)` in `app/iosApp/MuvissWidget/WidgetIntents.swift`.
- Verify: the group is in the list.

**O4. Register both App IDs and enable the group on each.** Owner.
- Open <https://developer.apple.com/account/resources/identifiers/list>. Click **+**, choose **App IDs**, then **App**.
- The app: Description `Muviss`, Bundle ID **Explicit** `com.codingpit.muviss`. Under Capabilities tick **App Groups** and nothing else. Do **not** tick Sign in with Apple: v1 is local-only (ADR 0024), and sync's Sign in with Apple moves to EPIC 32 (#75). Register. Open it again, **App Groups → Configure**, tick `group.com.codingpit.muviss`, Save.
- The widget: Description `Muviss Widget`, Bundle ID `com.codingpit.muviss.MuvissWidget`, App Groups only, the same group.
- If Xcode already created either App ID during automatic signing, edit it rather than adding a second one.
- Yields: two App IDs, both with App Groups configured. Neither target signs without both.
- Verify: both show App Groups as configured (verify in UI for the label).

**O5. First signed build on your own iPhone.** Owner. This is not CI.
- On your Mac: open `app/iosApp/iosApp.xcodeproj`. Under Signing & Capabilities, pick your team (Automatic signing lets Xcode create the provisioning profiles, so there is nothing to download by hand). If you want the team stored in the working copy, set `TEAM_ID` locally as in O2. Do not commit it.
- On the iPhone, turn on Developer Mode (Settings → Privacy & Security; verify in UI). Connect it, select it as the run destination, Run.
- Yields: the app running on a device signed by your team, which is the first real test of the App Group and the widget.
- Verify: the app launches with no dyld error; the widget can be added and shows a title once the app has run (device checklist 9.8, items 1 to 6).

**O6. Create the app record in App Store Connect.** Owner.
- Open <https://appstoreconnect.apple.com/apps>. Click **+**, then **New App**. Platforms: **iOS**. Name: `Muviss`. Primary language: **English (U.S.)**. Bundle ID: `com.codingpit.muviss` (from O4, it appears in the list). SKU: `muviss` (internal; users never see it). User Access: **Full Access**.
- Name availability is store-wide. If `Muviss` is taken, pick the closest free name and change `name.txt` in `fastlane/metadata/ios/en-US` and `es-ES` to match. Tell the agent, so the subtitle and listing stay consistent.
- Yields: the app record. CI does not need its numeric Apple ID; it finds the app by bundle id.
- Verify: `scripts/release/apple-setup.sh --verify` with `APPLE_ASC_KEY_ID`, `APPLE_ASC_ISSUER_ID` and `APPLE_ASC_P8_PATH` set (9.3) reports "app record found".

**O7. Create the App Store Connect API key, role Admin.** Owner, then the wizard stores three secrets.
- Open <https://appstoreconnect.apple.com/access/integrations/api>. If the page offers **Request Access**, the Account Holder must grant it first. Under **Team Keys**, click **+**. Name `Muviss CI`. **Access: Admin**. Generate.
- Copy the **Issuer ID** (above the keys table) and the new key's **Key ID**. Download `AuthKey_<KeyID>.p8`. Apple lets you download it **once**. Store it in a password manager.
- Why Admin and not App Manager: cloud-managed signing (`-allowProvisioningUpdates`, the `ios beta` lane) asks Apple for distribution certificates. App Manager does not get certificate access by default; an Admin has to grant "Certificates, Identifiers & Profiles". An Apple developer forum report also describes an Admin-role key getting a 403 "You haven't been given access to cloud-managed distribution certificates" (Apple developer forums, thread 776036: a report, not an Apple statement). If the first `ios beta` fails that way, the Account Holder grants the key's user access to Certificates, Identifiers & Profiles, and the failure is recorded on the release issue. **verify in UI** for the role list.
- Yields: `APP_STORE_CONNECT_KEY_ID`, `APP_STORE_CONNECT_ISSUER_ID`, `APP_STORE_CONNECT_KEY_P8` (the file's contents, the wizard reads the file). They are **secrets**.
- Verify: `gh secret list` shows all three; the `--verify` read in O6 succeeds with the key.

**O8. Sentry: token, organisation and project.** Owner, then the wizard stores three secrets.
- In Sentry, create an auth token (Settings → Account → API → Auth Tokens; verify in UI for the scopes needed: releases and org read). Note the organisation slug and the project slug. The Android build uses the same project.
- Yields: `SENTRY_AUTH_TOKEN`, `SENTRY_ORG`, `SENTRY_PROJECT`, all **secrets** (`release-rc.yml`, `release-promote.yml` and the Sentry workflows read them as secrets). Without all three, the dSYM upload is skipped with a notice, and the iOS crash gate cannot read anything.
- Verify: `gh secret list` shows the three names.

**O9. Store the GitHub secrets and variables.** The wizard does this. Summary of the names CI reads:

| Kind | Name | Set by | Read by |
|---|---|---|---|
| Variable | `APPLE_TEAM_ID` | O2 | `release-rc.yml` ios job, `ios beta` (signing) |
| Variable | `TESTFLIGHT_EXTERNAL_GROUPS` | O10 | `release-promote.yml` iOS step, `ios beta_external`. **Required** by the Sunday beta step |
| Variable | `TESTFLIGHT_GROUPS` | O10 | `ios beta` (internal group, optional) |
| Secret | `APP_STORE_CONNECT_KEY_ID` | O7 | every iOS leg |
| Secret | `APP_STORE_CONNECT_ISSUER_ID` | O7 | every iOS leg |
| Secret | `APP_STORE_CONNECT_KEY_P8` | O7 | every iOS leg |
| Secret | `SENTRY_AUTH_TOKEN`, `SENTRY_ORG`, `SENTRY_PROJECT` | O8 | `ios upload_dsyms`, the iOS crash gate (`app-store-phased-ramp.yml`, `app-store-brake.yml`, `release-promote.yml`) |

Verify: `scripts/release/apple-setup.sh --verify` lists each name as present (names only; it never prints a value).

The iOS job in `release-rc.yml` **skips with a notice**, not a failure, while `APPLE_TEAM_ID` or any of the three App Store Connect secrets is missing. That is deliberate: it is the reason the first pushes to a release branch stay green before the account exists. A skip is therefore not proof of anything. Check the job summary for "iOS release candidate skipped".

Not stored: the Developer ID `.p12` (`MACOS_DEVELOPER_ID_P12_BASE64`, `MACOS_DEVELOPER_ID_P12_PASSWORD`). That is desktop's DMG signing (EPIC 36, section 7), and no workflow reads it yet.

**O10. TestFlight groups, public link and Beta App Review information.** Owner, then the wizard stores two variables.
- Open the app in App Store Connect, then the **TestFlight** tab (verified against Apple's help page on 2026-10-17).
- **Internal Testing:** create a group, for example `Muviss internal`. Its name goes into `TESTFLIGHT_GROUPS` (optional).
- **External Testing:** click **+** next to External Testing, enter a group name (for example `Muviss beta`), Create. Its name goes into `TESTFLIGHT_EXTERNAL_GROUPS`. **Required**: the Sunday beta step fails without it.
- Select the external group, open the **Testers** tab, click **Create Public Link**. Choose **Open to Anyone**. The tester limit is optional: **Set Limit** accepts 1 to 10,000. Copy the link.
- **Beta App Review:** the first build of each version needs a review by Apple. The **Test information** dialog asks for a feedback email and contact information (both required; verify in UI which fields are mandatory). Demo notes: the repo's text says there is no account and no sign-in, that search needs the internet, and that the library works offline once titles are added. It is in `fastlane/metadata/ios/review_information/notes.txt`. Paste it if the dialog has a notes box (verify in UI).
- Yields: two group names and a public link.
- Verify: open the public link in Safari on a phone that is not signed in to Apple's TestFlight. It should offer to join the group.

**O11. Replace the review-contact placeholders.** Owner. Decision needed (below).
- `fastlane/metadata/ios/review_information/{first_name,last_name,email_address,phone_number}.txt` each say `REPLACE_BEFORE_SUBMIT`. `ios release` refuses to run while any file under `fastlane/metadata/ios` still contains that string.
- **Owner decision:** these files live in git. The repo goes public at the public flip (#81), so a personal phone number or email there would be public too. Either (a) fill them with a contact address you are happy to publish, or (b) enter the contact in App Store Connect's App Review Information box and change `IosRelease.placeholder_files` (and the Fastfile check) to skip the review-contact files. (b) is a small code change: ask the agent before the first `ios release`.
- Verify: `grep -rn REPLACE_BEFORE_SUBMIT fastlane/metadata/ios` prints nothing.

**O12. App Privacy questionnaire.** Owner. verify in UI for the labels.
- App Store Connect → Muviss → **App Privacy** → Get Started / Edit.
- Data collected: **Crash Data** only. Used for **App Functionality**, **Not linked to you**, **not used for tracking**. Our crash reports (Sentry) are sent only when the user keeps "Send crash reports" on (Settings → Privacy).
- Answer "no" for everything else. Accounts, analytics and advertising do not exist in v1.
- Device Backup (when it lands, #409) is the operating system's iCloud backup. We do not collect it, so do not declare it as our data. Apple's own backup is outside the questionnaire.
- Keep `app/iosApp/iosApp/PrivacyInfo.xcprivacy` and the widget's file consistent with this answer (#424 checks them against Xcode's privacy report).
- Yields: the published "Data Not Linked to You: Crash Data" label.
- Verify: the App Privacy page shows that label and no tracking.

**O13. Age rating.** Owner. verify in UI for the questions.
- App Information → **Age Rating** → answer the questionnaire. The app generates no user content and has no chat or purchases in v1. It does show third-party catalogue metadata (TMDB titles, posters and overviews), and that can include mature titles. Answer about the app's own content and check the result. The plan expects **4+**. If Apple gives a higher rating, record it on the release issue and do not argue it away.
- Verify: the rating shown on the App Information page.

**O14. Price and availability.** Owner.
- Pricing and Availability: **Free**. Choose the territories. Nothing in v1 is paid (sync and co-watch are EPIC 32, after v1).
- Verify: price shows 0 for all territories.

**O15. EU Digital Services Act: trader status.** Owner. A legal answer, not ours to give.
- Apple asks each developer in the EU DSA flow whether they are a **trader**. verify in UI for the page: the prompt arrives from App Store Connect and the answer is made there.
- If you are a **trader**, the EU App Store shows your address, phone and email on the app's page. For an Individual that is the address you gave in O1. If you are a **non-trader**, the address is not shown. Third-party summaries of Apple's rules say that a developer distributing free apps with no ads, no purchases and no other revenue may not need to provide the information. v1 is free with no ads, so the honest answer is probably non-trader, but the decision is yours.
- Apps without this answer were removed from the EU App Store in 2025 (reported). Do not leave it blank.
- Yields: a trader or non-trader declaration on the account. Verify: the declaration shows in App Store Connect and no "trader status" prompt remains.

**O16. Export compliance.** Owner confirms, CI already declares it.
- `ITSAppUsesNonExemptEncryption` is `NO` in both `app/iosApp/iosApp/Info.plist` and `app/iosApp/MuvissWidget/Info.plist` (checked in the repo). That answers Apple's encryption question for every build. The value assumes the app uses only exempt encryption (HTTPS and the system's cryptography). Confirm that is still true.
- Verify: on the first upload, no per-build compliance question appears (ADR 0033 assumption (d); #429 item 7).

**O17. Support URL and privacy URL.** Owner.
- `fastlane/metadata/ios/en-US/support_url.txt` is `https://muvissapp.com/` because the site has no support page yet (#423). That is acceptable for a first submission only if you are content for support mail to go through the landing. Decide, and add a page or keep the URL on the release issue.
- `privacy_url.txt` is `https://muvissapp.com/privacy/`. Open it in a private window before submitting; a 404 blocks the listing.
- Verify: both URLs open in a private window.

**O18. Cut the first release candidate and see what happens.** Owner cuts; CI builds.
- Cut with `scripts/release/start-release.sh X.Y.Z` (or let the Monday train do it). Pushing `release/X.Y.Z` runs `release-rc.yml`: it tags `vX.Y.Z-rcN`, uploads the Android build, then runs the iOS job on macOS.
- The iOS job: checks the secrets (O9); sets up Xcode and Kotlin/Native; archives the Release build with cloud-managed signing; checks its `CFBundleVersion` equals the RC's version code; uploads it to TestFlight **internal** testers with `ios beta`; uploads dSYMs to Sentry if the three Sentry names are set.
- Expect: the upload takes a while to process. The build then appears under TestFlight, with the RC's version number and its build number equal to the versionCode from `scripts/release/version-code.sh`. The job summary says "iOS candidate … uploaded to TestFlight (internal)".
- Failure to watch for: the 403 about cloud-managed certificates (O7). Anything else, paste the first error into the release issue.
- Verify: `gh run list --workflow=release-rc.yml --limit 3`, then the build in TestFlight.

**O19. Beta App Review (Sunday, before the App Store).** CI does it; the owner watches it.
- On the Sunday step, `release-promote.yml`'s iOS step runs `ios beta_external`: it sends the RC's build to the external group and submits it to Beta App Review. Apple reviews the first build of a version. Latency is unknown (third-party guides say about a day; verify on the first live run).
- Record submit-to-approval time on the release issue (#429 item 5). If Apple rejects the RC, iOS holds with the reason; Play is not held (9.4).

**O20. App Store production (after the merge).** CI does it when everything is in place.
- The App Store step runs only once `release/X.Y.Z` is in `main` (Play's production step does that merge) and Beta App Review has approved the build. `ios release submit_for_review:true` sends the TestFlight build with its metadata, submits it for review, with automatic release and a seven-day phased release.
- Before the first submission you need: O11 done, O12 to O17 answered, screenshots for the device classes in App Store Connect (`fastlane/screenshots/ios`; #425 tracks the 6.9-inch, 6.5-inch and 13-inch iPad sets), and the app record's price and availability.
- `ios release` refuses while any metadata file holds `REPLACE_BEFORE_SUBMIT`.

### 9.3 Checks you can run yourself

```bash
scripts/release/apple-setup.sh --verify
# with the key in hand, it also reads the app record (read only):
APPLE_ASC_KEY_ID=… APPLE_ASC_ISSUER_ID=… APPLE_ASC_P8_PATH=~/Downloads/AuthKey_….p8 \
  scripts/release/apple-setup.sh --verify
```

`--verify` reads the repository's secret and variable **names** with `gh secret list` and `gh variable list`, reports anything CI needs that is missing, and exits non-zero if a required name is absent. With the three `APPLE_ASC_*` variables set it makes one read-only App Store Connect call (`GET /v1/apps?filter[bundleId]=…`) through `fastlane/app_store_connect.rb`. It also checks that fastlane lists the `beta_external` lane. It uploads nothing and changes no setting. It does **not** prove the key's role: only the first cloud-managed signing run does that (O18).

### 9.4 The staged train for iOS (ADR 0033)

iOS walks the same three stages as Play, one week apart, on the **same RC binary**. Each platform has its own stage in the release issue's marker. Shared gates (release-blocker, a fix PR into the branch, CI, draft notes) hold both platforms. The iOS steps are skipped with a notice while the App Store Connect key is unset, and Play runs as before.

| When | iOS step | What happens | Where |
|---|---|---|---|
| Monday, after the RC | internal | `ios beta` uploads the RC to TestFlight internal testers | `release-rc.yml`, `ios` job |
| Sunday, week 0 | external (Beta App Review) | `ios beta_external` sends the RC's build to the external group and submits it to Beta App Review | `release-promote.yml`, iOS step |
| Sunday, week 1 | App Store | Once `release/X.Y.Z` is in `main` and Beta App Review has approved the build: `ios release submit_for_review:true` (automatic release, seven-day phased) | `release-promote.yml`, iOS step |
| Daily | phased ramp | Pauses the live phased release while the iOS crash gate fails; resumes only its own pause once the gate passes | `app-store-phased-ramp.yml` |
| Any time | brake | `pause`, `resume`, or `ios-only-override` for a version | `app-store-brake.yml` (manual) |

Rules the train enforces:

- **App Store needs the merge.** iOS production runs only after `release/X.Y.Z` is in `main`, which Play's production step does. A Play hold therefore holds iOS production. An iOS hold never holds Play.
- **One unreleased version at a time (supersede, ADR 0033 rule (a)).** When N is due and N-1 is in Waiting for Review or In Review, N-1 is removed from review and marked superseded on its issue; N is submitted in its place (its fixes are already in N, because ADR 0030 forwards them). A rejected N-1 is superseded when N is due, and the rejection reason is copied onto N's issue. N-1 in *Pending Developer Release* is never superseded: N waits, because submitting N could end N-1's rollout.
- **A new release ends the previous phased rollout (unverified).** This is the owner's premise, not something Apple documents. It is the first thing to check on the first live run (9.6, item 1). If N ends N-1's ramp early, the release issue says so.
- **`ios-only-override`** (`app-store-brake.yml`, `action: ios-only-override`, `version`) is the one way past a Play hold. It merges the release PR itself, submits iOS, and records on the issue that Play is **not** promoted. The Sunday Play step will not touch that version (`ios_override_on`). Promote Play by hand (`play-promote.yml`) once its gates pass.
- **Crash gate, per platform.** iOS builds report release `com.codingpit.muviss.ios@<name>+<code>` (`crashReleaseNamespace` in `app/shared`, #422). Android keeps `com.codingpit.muviss@…`. `sentry_gate.rb` asks for its own platform's namespace, so an iOS crash never holds Play and the reverse. A Sentry outage pauses nothing: the ramp reads it as "could not look" and leaves the rollout alone.
- **Pause budget (unverified).** The train assumes Apple allows 30 paused days per version, cumulative across pauses; the first live run confirms it (9.6, item 11). A version that has used them is left for an owner decision, and its issue says so.
- **Brake.** Use `app-store-brake.yml`, not "Remove from Sale". Removing the app from sale ends that version's phased release for good.
- **Holds are recorded per platform**, on the release issue's marker. Markers written before iOS joined the train still parse and render "not on this train".

### 9.5 Lanes and scripts

`fastlane/Fastfile`, `platform :ios` (helpers in `fastlane/ios_release.rb`, tests in `fastlane/test/`):

- `ios beta`: archives the RC (`iosApp`, Release, App Store export, cloud-managed signing), refuses a build whose `CFBundleVersion` is not the RC's version code, and uploads it. With `TESTFLIGHT_GROUPS` set it waits for Apple's processing and distributes the build to those internal groups (never a beta review); without it, it only uploads.
- `ios beta_external`: sends the RC's build to the external group (public link) and submits it to Beta App Review. It uses `pilot`'s distribute-only mode (`distribute_only: true`): the build is picked by version and build number and never re-uploaded. The version's `fastlane/release-notes/vX.Y.Z/en-US.txt` is its *What to Test*, which external testing requires (#429 item 9).
- `ios upload_dsyms`: sends the archive's dSYMs to Sentry. Skips with a notice unless `SENTRY_AUTH_TOKEN`, `SENTRY_ORG` and `SENTRY_PROJECT` are all set.
- `ios release`: sends the TestFlight build to the App Store listing. It takes the **RC** tag (`tag:vX.Y.Z-rcN`, or `RELEASE_TAG`) and refuses any other: the build was uploaded under the RC's version code, and the final tag's matches no TestFlight build. Submits only with `submit_for_review:true`. Refuses while `fastlane/metadata/ios` still holds `REPLACE_BEFORE_SUBMIT`.

Scripts: `ruby fastlane/ios_promote.rb run | ramp | brake pause|resume X.Y.Z | override X.Y.Z` (dry run unless `DRY_RUN=false`), and `scripts/release/apple-setup.sh --verify`.

The Kotlin/Native release framework has line tables only because `app/shared/build.gradle.kts` passes `-Xadd-light-debug=enable` to the release binary. Without it the dSYM symbolicates Kotlin frames by name only (#428 checks the result).

### 9.6 The first-live-run checklist (#429)

Each item is an Apple behaviour that ADR 0033 assumes. Record the answer on the release issue. Nothing here is verified yet.

1. A newer release ends the previous phased rollout. Read N-1's `phasedReleaseState` after N is released.
2. The phased-release states are Apple's documented `PhasedReleaseState` (`INACTIVE`, `ACTIVE`, `PAUSED`, `COMPLETE`); only `PAUSED` reads as paused. Confirm with `GET /v1/appStoreVersions/{id}/appStoreVersionPhasedRelease` after the first pause.
3. A `PATCH` to `ACTIVE` in Pending Developer Release answers 409 (a forum report). The train only pauses a live rollout; confirm it never reaches that state.
4. Cancelling a review uses `PATCH /v1/reviewSubmissions/{id}` with `canceled: true`, found through `GET /v1/reviewSubmissions` and its `items`.
5. Beta App Review latency: record submit to approval.
6. Beta App Review on a second RC of the same version: does it need a full review?
7. No per-build export-compliance question once `ITSAppUsesNonExemptEncryption = NO` is in both Info.plists.
8. TestFlight builds expire after 90 days; the cut-to-submit gap is about thirteen days.
9. `pilot`'s distribute-only mode in `ios beta_external` (`distribute_only: true`, `submit_beta_review: true`, the release notes as *What to Test*): the build reaches the external group and Beta App Review.
10. Resume after a Sentry pause: the gate resumes only its own pause, after it passes on a daily run.
11. The 30-day pause budget is cumulative per version, gate and brake pauses alike. A gate pause still holding at 30 days is handed to the owner. At 25 paused days, decide before it runs out.
12. `GET /v1/builds` (`filter[version]`, `filter[preReleaseVersion.version]`) and `GET /v1/builds/{id}/betaAppReviewSubmission` have the shapes the code assumes.
13. The iOS crash release string `com.codingpit.muviss.ios@…` appears on a real iOS build in Sentry (#422).

Plus #428 (the release dSYM has Kotlin line tables and the Sentry framework's dSYM ships) and #424 (the privacy manifests match Xcode's report).

Follow-ups for this train: #430 (Play's Sunday complete closes the release issue while iOS is still ramping), #431 (iosTest coverage for the iOS namespace).

### 9.7 Versioning and the bundle identifier

- `CFBundleVersion` is the Android `versionCode` (`scripts/release/version-code.sh` on an exact `vX.Y.Z[-rcN]` tag, the same formula as §3). `CFBundleShortVersionString` is the numeric `X.Y.Z` with any `-rcN` dropped (App Store Connect rejects a suffix).
- Both targets (`iosApp` and `MuvissWidget`) run the "Set version from git" phase, which calls `scripts/ios/set-version.sh`. They must agree, or Apple rejects the upload (ITMS-90473). An untagged build reads `0.1.0`, and `Config.xcconfig`'s `CURRENT_PROJECT_VERSION`/`MARKETING_VERSION` are only a fallback for a non-git checkout. Details in `app/iosApp/README.md`.
- Bundle identifier `com.codingpit.muviss` (`PRODUCT_BUNDLE_IDENTIFIER` in `Config.xcconfig`), the same as Android's `applicationId`. The widget is `com.codingpit.muviss.MuvissWidget`.
- Export compliance is declared in both Info.plists (O16). The background-refresh identifier `com.codingpit.muviss.refresh` appears in `BGTaskSchedulerPermittedIdentifiers`, and `UIBackgroundModes` is `fetch`.

### 9.8 Device checklist: what only a signed build can show

Run on a TestFlight build (or your own device build, items 1 to 6), on an iPhone and an iPad where the item says so.

1. **Widget and App Group** (#23, #414): add the Watch next widget; it names the next unseen episode; Seen then Undo behave, and the Progress tab agrees.
2. **Airplane mode**: force-quit, turn on airplane mode, check the widget still names an episode. This proves the App Group carries the catalogue (ADR 0016, ADR 0015). A blank widget means the extension is reading its own empty database.
3. **Widget colours**: light and dark, against the app (#414).
4. **Share sheet and copy** (#186, #415): copy and share on iPhone and iPad, from Settings and from a title. On iPad the popover must anchor (#416 is the `keyWindow` risk on multi-scene iPad).
5. **Import, Spanish UI, notifications** (#240): import picker, share, Spanish strings, and the notification prompt on a fresh install.
6. **Onboarding and the notification nudge** (#324): fresh install, then the prompt on the first notifications switch.
7. **Background refresh** (#417): the BGTask runs and resubmits itself; the pending request exists after a run.
8. **KSafe** (#322): the Keychain item for the Own TMDB Key survives a relaunch, on device.
9. **Muted genres** (#388): mute a genre and check the deck, per target.
10. **Sync triggers** (#114): the background trigger and foreground behaviour with sync turned on (EPIC 40), if a build with sync is in use. v1 builds have no sync UI.
11. **Darwin engine and live TMDB** (#92): search, popular rows and a detail page on the Darwin engine.
12. **Sentry test crash, symbolicated** (#428): Settings → About, tap the version seven times, press **Send test crash**. Relaunch. In Sentry, the event is `MuvissTestCrash`, with release `com.codingpit.muviss.ios@<name>+<code>`, `environment=production`, and Kotlin frames by name (not raw addresses). A raw address means the dSYM upload or the line tables failed (#428).
13. **Device Backup restore** (#419, #420): only after the Device Backup epic (#409) lands. Turn the setting on, back up, restore onto a second device, check the library and that the Keychain items behave as the Device Backup decision record says.
14. **Simulator facts, for reference**: the iOS 26 runtime is needed for `-destination`; the App Group works on the Simulator without a team, so the Documents fallback in `DatabaseFactory.ios.kt` is exercised only on device builds without a team. A passing Simulator run is not evidence that the fallback works.

Expect OAuth sign-in to fail with "cannot connect to the server" if a sign-in is left open for about ten minutes: the PKCE state expires. That is not a configuration fault; retry.

### 9.9 When Apple rejects

- **Beta App Review rejects the RC.** The iOS production step is held with the reason, and the reason is copied to the release issue. Read the reason in the TestFlight build's review message (verify in UI). Fix it on the release branch, so the fix forwards (ADR 0030), and cut a new RC. A rejection the RC cannot answer is a `release-blocker`. Play is not held.
- **App Review rejects the App Store submission.** Read the reason in App Store Connect's Resolution Center (verify in UI). If the fix needs code, follow the RC route above. If it needs only metadata or review notes, change the files under `fastlane/metadata/ios` and run `ios release` again. The rejected version is marked superseded when the next version is due (9.4).
- **Apple asks for information in the Resolution Center.** Answer there. The answer belongs on the release issue too.
- **You need to withdraw a version.** Use the brake (`app-store-brake.yml`, `pause`). Do not use "Remove from Sale".
- If the rejection is a policy question you cannot settle, ask the agent to file it as an issue with the Apple reference and the reason copied in. Do not guess an answer to App Review.

### 9.10 Deferred, and not part of v1

Sign in with Apple, the in-app purchase product and `ASWebAuthenticationSession` (#33) belong to EPIC 32 (#75). The dark and tinted icon slots are empty (#34). iOS is arm64-only (#35). The App Store screenshots for the device classes are #425.

### Startup wiring, notifications and the widget (reference)

- **`AppDelegate.swift`** calls `IosAppStartup.shared.start()` from `application(_:didFinishLaunchingWithOptions:)`, the one hook Apple guarantees runs on every launch, including a background BGTask launch. `IosAppStartup.kt` starts crash reporting first (`MuvissCrashReporting.start`), then Koin, registers the `BGTaskScheduler` task and configures `UNUserNotificationCenter`.
- **`MainViewController.kt`** feeds `IosNotificationCenter.pendingDeepLinkMediaId` into `MuvissApp(deepLinkMediaId, onDeepLinkConsumed)`.
- **Notifications** mirror Android's new-episodes trio in Kotlin (`IosBackgroundRefresh.kt`, `IosNotificationCenter.kt`). Permission is requested through `SettingsApi.setNotificationsEnabled`, which asks the OS first (ADR 0029). Nothing asks cold.
- **Widget:** Swift under `app/iosApp/MuvissWidget`, reaching Kotlin only through `IosWidgetBridge` (flat strings). CI builds and links it on every PR, but nothing about its behaviour has run on a device (9.8).
- **Database:** `muviss.db` lives in the App Group container (ADR 0016). `DatabaseFactory.ios.kt` moves a pre-v9 database there once, and falls back to the Documents path when the entitlement is missing. Do not remove that fallback: without it a fresh clone crashes on launch.
- **Sentry** reads its DSN from `SENTRY_DSN` at build time, the same as Android.
- **Static framework:** `Shared` is a static framework, so The Xcode targets link `-framework Sentry -lsqlite3` and the app embeds `Sentry.framework` through a script phase (`SENTRY_XCFRAMEWORK_SLICE`). These were the 50 missing symbols found on the Simulator on 2026-09-02, and CI's `ios` job on `macos-latest` now builds them on every PR.

## 10. Web: the landing (muvissapp.com) and the Wasm app — EPIC 13 / issue #16

### The landing owns GitHub Pages

`.github/workflows/deploy-pages.yml` builds the Astro site in `website/` and
deploys `website/dist` to this repo's GitHub Pages on every push to `main`
that touches `website/**` or `docs/PRIVACY.md` (plus manual
`workflow_dispatch`). `website/public/CNAME` claims `muvissapp.com`. The
site's `/privacy/` page is rendered at build time from `docs/PRIVACY.md`,
which is why that file triggers a deploy: it is the Privacy Policy URL the
store listings give (`docs/store/LISTING.md`). PRs touching the site get a
cheap `website.yml` check (one `npm run build`, no Gradle), path-filtered so
it runs only for the site and a website-only PR runs no Gradle lane.

```bash
cd website && npm install && npm run dev   # local dev server
cd website && npm run build                # → website/dist
```

What the landing lists is every **free** feature. Sync and co-watch are paid
(they need an Entitlement, ADR 0018/0022) and are deliberately absent. Keep
it that way when adding copy (`website/src/i18n/{en,es}.ts`). Store badges are
driven by `website/src/config/links.ts`: `null` renders "Coming soon" (#170).
Screenshots are captured by hand, shot list in `website/README.md` (#168).

**The Wasm web app is not hosted anywhere right now.** It used to deploy
from this same workflow to the Pages root. A Pages site carries one custom
domain and the landing owns it, so the app moves to `app.muvissapp.com`
(#167). The old steps are in this file's git history.

### Going live (blocked on #81 / #66)

**This repo is private, and GitHub Pages on a private repo needs a paid
plan.** This was confirmed directly, not assumed:

```bash
gh api -X POST repos/{owner}/{repo}/pages -f "build_type=workflow"
# → 422 "Your current plan does not support GitHub Pages for this repository."
```

Once the repo is public (#81), the one-time setup is #169:

1. DNS at the registrar:
   - Apex `A` records to `185.199.108.153`, `185.199.109.153`, `185.199.110.153` and `185.199.111.153`.
   - Apex `AAAA` records to `2606:50c0:8000::153` through `2606:50c0:8003::153`.
   - `CNAME www → juanjecilla.github.io`.
2. **Settings → Pages → Build and deployment → Source → GitHub Actions**,
   then run the workflow.
3. Set the custom domain to `muvissapp.com`, then **Enforce HTTPS** once the
   certificate is issued.

### Build commands

```bash
./gradlew :app:webApp:wasmJsBrowserDevelopmentRun    # local dev server, Wasm
./gradlew :app:webApp:wasmJsBrowserDistribution       # production build → app/webApp/build/dist/wasmJs/productionExecutable
./gradlew :app:webApp:jsBrowserDistribution           # production build, JS target → app/webApp/build/dist/js/productionExecutable
```

Either distribution can be smoke-tested locally without Gradle by serving
the output directory statically, e.g. `python3 -m http.server 8080` from
inside the `productionExecutable` directory, then opening
`http://127.0.0.1:8080/index.html` — real browsers, not `file://`, are
required (module workers and the wasm fetch both need an HTTP origin).

### PWA manifest + icons

`app/webApp/src/webMain/resources/manifest.webmanifest` + `icons/` (192,
512, a maskable 512, a 32×32 favicon, and a 180×180 Apple touch icon) are
regenerated from the same `docs/design/icon-src/` masters as every other
platform's icon (#163). `theme_color`/`background_color`
in the manifest and the `<meta name="theme-color">` in `index.html` match
`MuvissPalette` (`:core:designsystem`), corrected from the old purple
placeholder in #163. Verified installable in Chrome (the manifest
resolves, icons load, `display: "standalone"` is honored) as part of this
epic's live-browser check — an actual "Install" prompt/App icon on a home
screen was not captured (no mobile device in this environment), but the
manifest itself validates and every icon file 200s.

No service worker: offline asset caching was explicitly scoped out of this
epic (manifest + installability is the v1 bar) — the app still needs a live
network for TMDB search/discover regardless, so full offline support is a
separate, larger follow-up (a cache-first service worker for the shell plus
whatever staleness story the collection/progress screens would need for
already-fetched poster images).

### Web persistence (SQL.js in a Web Worker)

See `docs/adr/0008-migration-baseline-and-deferred-web-persistence.md`'s
2026-07-16 amendment for the full story. Short version: real read/write
persistence now works on both `js` and `wasmJs`, backed by SQLDelight's
`web-worker-driver` running SQL.js (SQLite-to-wasm) inside a Web Worker —
and since EPIC 24 it is **durable on a best-effort basis** — in the tab
that holds the writer lock, and as long as IndexedDB writes succeed. A
second tab runs in memory only (`ReadOnlyTab`), and a browser without Web
Locks never persists (`NotPersisted`); a snapshot write that fails is only
logged by the worker and reported nowhere (#292). So:
our fork of the worker
(`core/database/src/webWorker`) restores an IndexedDB snapshot on open and
re-exports it shortly after each committed write, and only one tab persists
(CLAUDE.md, "Web + DB"). Getting the worker to actually load in a webpack-bundled build needed
two non-obvious fixes, both in `app/webApp/webpack.config.d/copy-sqljs-wasm.js`
and both discovered only by driving a real build's output in a real browser
(a clean `wasmJsBrowserDistribution` alone does not catch either):

1. The `new Worker(new URL(...))` construction has to appear as one
   untouched `js(...)` string (see `sqljsWorker()` in `core/database`'s
   `DatabaseFactory.web.kt`) — split across two Kotlin calls, it still
   compiles and still produces a webpack asset, but webpack's *native*
   worker-chunking never triggers, so the worker's own `import "sql.js"`
   never gets bundled and the browser can't resolve it (silent hang, no
   console error — the failure is inside the worker's own module load).
2. `sql.js`'s loader references Node's `fs`/`path`/`crypto` for a code path
   this browser build never takes; webpack 5 still needs `resolve.fallback`
   entries disabling all three or the build fails outright.

### Manual smoke test before relying on the deployed web build

1. `./gradlew :app:webApp:wasmJsBrowserDistribution`, serve the output
   locally (see above), open it in a real browser.
2. Confirm the splash spinner clears and the app renders (bottom nav +
   Search screen) within a few seconds.
3. Settings screen: toggle the theme; confirm it round-trips (proves a
   write + reactive re-read against the real web driver, not just that the
   canvas painted).
4. Library/Progress/Profile screens: confirm they load without an error
   state (each screen's `.catch { }` would otherwise surface a visible
   error instead of a silent hang or crash — see ADR 0008).
5. Reload the page; confirm the theme you chose in step 3 is still set.
   That shows a snapshot was written and restored — not that every write
   since was, since a failed snapshot write is silent (#292).

## 11. Home-screen widgets — EPIC 22

Android needs nothing here: the Glance widget ships in the debug and release
APK, and appears in the launcher's widget picker under **Muviss** with no
extra setup.

iOS is the opposite. The Kotlin side, the Swift sources and the Xcode target
are all checked in and compile, but a widget extension that shares the app's
database needs an **App Group**, and an App Group needs a real Apple
Developer team. Nothing in this repo can create one. Until the steps below
are done, `DatabaseFactory.ios.kt` falls back to the app's Documents
directory (ADR 0016) — the app works exactly as before and the widget shows
an empty library.

### iOS: one-time setup

The Apple side is section 9, steps O2 to O4 (Team ID, App Group, both App IDs), and the
owner's checklist is section 9.1. Four identifiers must agree, character for character:
the two entitlement files (`app/iosApp/iosApp/iosApp.entitlements`,
`app/iosApp/MuvissWidget/MuvissWidget.entitlements`), `MUVISS_APP_GROUP` in
`core/database/src/iosMain/.../DatabaseFactory.ios.kt`, and the
`UserDefaults(suiteName:)` in `app/iosApp/MuvissWidget/WidgetIntents.swift`.
On a device build, until the App Group is registered and enabled on both App IDs,
`DatabaseFactory.ios.kt` falls back to the Documents directory (ADR 0016) and the widget
shows an empty library. The Simulator has the App Group without a team (item 14).

### iOS: what to check in Xcode

The `MuvissWidget` target is built by CI's `ios` job, as a dependency of
`iosApp` under `CODE_SIGNING_ALLOWED=NO`, so it compiles and links on every
PR. What CI cannot show is behaviour on a signed device, so expect to adjust:

- **Framework linking.** The target links the static `Shared` framework via
  `OTHER_LDFLAGS = -framework Shared` and a `FRAMEWORK_SEARCH_PATHS` entry
  pointing at `app/shared/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)`.
  If the linker cannot find it, check where
  `:app:shared:embedAndSignAppleFrameworkForXcode` actually wrote the
  framework for your Xcode version and fix the search path.
- **Deployment target and Swift version** are set to match the app (18.0,
  Swift 5.0). Interactive widget buttons (`Button(intent:)`) need iOS 17+, so
  18.0 is comfortably fine.
- **The scheme.** Xcode will offer to create a `MuvissWidget` scheme the
  first time; the shared `iosApp` scheme builds the extension as a dependency
  either way.

### iOS: manual smoke test

1. Run the app once on a device or simulator so the episode catalog fills
   (it ships empty — ADR 0015).
2. Long-press the home screen > add the **Watch next** widget.
3. Confirm it names the next unseen episode of a show you are part-way
   through, in the app's amber, and resizes between one, three and five rows.
4. Tap **Seen**; confirm the row shows "Marked seen" with **Undo**, and that
   the app's Progress tab and that episode's watch history both agree.
5. Tap **Undo**; confirm both the tick and its play row are gone.
6. **Turn on airplane mode, force-quit the app, and check the widget still
   names an episode.** This is the whole point of the stored catalog; if it
   goes blank, the App Group is not wired up and the extension is reading its
   own empty database.

## 12. Remote config (ConfigCat) — EPIC 45 / issue #297

Remote Config (ADR 0026) serves two values without a release: `byokEnabled`
and `tmdbFallbackReadToken`. It is **off until you create the product**: a
build with a blank `CONFIGCAT_SDK_KEY` binds a defaults-only implementation
that never makes a request, which is what every local, CI and current release
build does.

Owner steps to switch it on:

1. Create a ConfigCat account and a product ("Muviss") with **EU data
   governance** (the code asks for it; a product on the global CDN would
   mismatch). Free tier is enough: two settings, one fetch per active device
   per hour at most.
2. Create the two settings with exactly these **keys**:
   `byokEnabled` (boolean, default `false`) and `tmdbFallbackReadToken`
   (text). ConfigCat will not save an empty text value, so give it a
   placeholder such as `none`: the app only uses the value when it has the
   shape of a TMDB v4 read token (`eyJ…`, three dot-separated parts), and
   anything else means "use the built-in credential".
3. Copy the environment's **SDK key**. It ships in the binary like the TMDB
   credential, so it must be the read-only key ConfigCat shows; never a
   management API credential.
4. `gh secret set CONFIGCAT_SDK_KEY` (or `scripts/release/owner-setup.sh`), and
   `CONFIGCAT_SDK_KEY=…` in `local.properties` for a local build. Set
   `BYOK_ENABLED=true` locally to see the compiled default flip.

**Rotating the TMDB credential** (the reason this exists): put the new v4 read
token in `tmdbFallbackReadToken`. Installs pick it up on their next foreground
(within an hour), and a cold start applies the stored copy before its first
TMDB request. Background workers (new-episode checks, background sync) never
fetch; they use whatever the last foreground stored. The served token is no more
secret than the one in the APK — the point is rotation, not secrecy. Set it back to
`none` to fall back to the built-in credential.

`docs/PRIVACY.md` and `docs/store/DATA_SAFETY.md` already describe the request
(it exposes the device IP to ConfigCat and sends nothing about the user), so
setting the secret needs no further copy change.

## 13. Feature Request form (Tally) — EPIC 53 / issue #447

The **Request a feature** row in Settings > About is built in but **dark**: it only appears once the app has a form URL. See ADR 0035. Owner steps, once:

1. In Tally, create an API key (Settings > API keys). Run `read -rs TALLY_API_KEY && export TALLY_API_KEY` (bash or zsh) and paste the key at the silent prompt, so it never lands in your shell history. It stays with you; it never goes in the app or in CI.
2. `scripts/feedback/tally-form.sh create` makes a **draft** from `scripts/feedback/tally-form.json` and prints the form id and `https://tally.so/r/<id>`. **First run, check the draft in the dashboard** (issue #450): the four hidden fields (`version`, `platform`, `os`, `locale`), the optional email, and that "Email me on each submission" is on. The script's block shapes were not verified against the live API, and the create response has no URL field, so the script derives it from the id.
3. `scripts/feedback/tally-form.sh publish <id>` publishes it, sending the blocks along with the status (a status-only PATCH leaves the public page with no fields, only a Submit button). `update <id>` re-applies the checked-in definition as a draft after you edit the JSON, so run `publish` again afterwards.
4. Turn it on, either way:
   - **Compiled default:** `FEATURE_REQUEST_URL=https://tally.so/r/<id>` in `local.properties` / CI secrets (needs a release).
   - **ConfigCat** (no release needed): create two text settings in the product from §12 with exactly these keys: **`featureRequestProvider`**, default `tally`, and **`featureRequestUrl`**, default the published `https://tally.so/r/<id>`. A setting that does not exist falls back to the compiled default, and that default for the URL is blank, so until `featureRequestUrl` exists with a real URL the row stays hidden. ConfigCat will not save an empty text value; to keep the setting while the row is off, use a placeholder such as `none`.

   Only an absolute `https://` URL shows the row. A blank value, a placeholder, a URL without its scheme or an `http://` one all hide it.
5. Verify end to end: open the app, tap the row, and submit a test response; confirm the email arrives and `version`, `platform` and `locale` are filled. `os` is filled on Android, iOS and desktop only; the web app cannot read the OS version and leaves it empty.

**Switching to Typeform** (the fallback, if Tally ever fails us): create the form by hand in Typeform with the same four **hidden fields** (`version`, `platform`, `os`, `locale`), turn on **Send email for new responses** in the form's notification settings (Typeform does not email responses by default) and confirm it with a test submission, then set ConfigCat `featureRequestProvider` = `typeform` and `featureRequestUrl` = its public URL. Typeform reads hidden fields from the URL fragment, so the provider key must change with the URL. Its free plan stops collecting after about 10 responses a month, so treat it as a stop-gap. `featureRequestProvider` = `off` hides the row whatever the URL is.

Each response (of either form) reaches you by email. It also becomes a GitHub issue within the hour once its form's id is an Actions secret next to `TALLY_API_KEY`: `TALLY_FEATURE_REQUEST_FORM_ID` for feature requests, `TALLY_BUG_REPORT_FORM_ID` for bug reports (below). See 'Feature requests to issue' under the Ops Bridge.

### Bug Report form (#449)

A second row, **Report a problem**, sits under **Request a feature** in Settings > About and opens a second Tally form (`scripts/feedback/tally-bug-report.json`: what happened, what was expected, an optional email, the same four hidden fields). It follows the same rules: dark until its URL is set, the same provider switch (`featureRequestProvider`, which governs both rows; `off` hides both), no new data in the link. Because the switch is shared, **switching to Typeform moves both forms**: create the bug report form in Typeform too and set `bugReportUrl` to it in the same change, or clear `bugReportUrl` to hide the row. A Tally `bugReportUrl` left behind would still show the row, but its link would carry the four values in a fragment that Tally does not read.

1. `TALLY_FORM_DEFINITION=scripts/feedback/tally-bug-report.json scripts/feedback/tally-form.sh create`, then `publish <id>` with the same variable set (publish re-sends the definition).
2. `BUG_REPORT_URL=https://tally.so/r/<id>` in `local.properties` / CI secrets, or the ConfigCat text setting **`bugReportUrl`**.
3. Add the Actions secret `TALLY_BUG_REPORT_FORM_ID` so the Ops Bridge turns each report into an issue.
