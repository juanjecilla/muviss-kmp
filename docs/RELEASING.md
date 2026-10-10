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

- `versionCode` = `git rev-list --count HEAD` (total commits on HEAD). It
  only ever increases, which is all Play requires, and is well-defined
  before the first tag exists.
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
`com.codingpit.muviss@<versionName>+<versionCode>` and `dist` the versionCode —
exactly what the Sentry Gradle plugin stamps on the mapping it uploads. They
must stay identical or a trace cannot find its mapping (`MuvissCrashReporting.releaseOf`
and `app/androidApp/build.gradle.kts`). `environment` is `SENTRY_ENVIRONMENT`
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

## 5. Branches, the release train and CI (ADR 0025)

### The flow

```
feature/x ──squash──► develop ──(Monday night train, or start-release.sh)──► release/1.3.0
                         ▲                                                       │ every push:
                         │                                                       │  tag v1.3.0-rcN → signed AAB
                         │                                                       │  → Play internal + GitHub pre-release
                         │                                              PR, merge commit
                         │                                                       ▼
                         └──── back-merge PR (merge commit) ◄──────────────── main ── tag v1.3.0
                                                                                  → promote rcN to production 20%
                                                                                    (approve in the `production` environment)
                                                                                  → ramp 50% day 2, 100% day 4
                                                                                  → GitHub Release: rcN's AAB + DMG/DEB/MSI
hotfix/1.3.1 is cut from main (start-release.sh 1.3.1 --hotfix) and goes the same way.
```

| workflow | trigger | does |
|---|---|---|
| `ci.yml` | PRs into `develop`/`main`; pushes to `develop`, `main`, `release/**`, `hotfix/**` | build, tests, desktop, iOS |
| `pr-guards.yml` | PRs into `develop`/`main`, incl. retitle/relabel | `main-source-guard`, `release-notes-guard`, `landing-guard`, release tooling tests |
| `release-train.yml` | nightly 23:00 UTC; acts on `RELEASE_TRAIN_DAY` (default `1`, Monday); dispatch | cut `release/<next>` if `develop` has new commits and no release is open |
| `release-rc.yml` | push to `release/**`, `hotfix/**` | tag `vX.Y.Z-rcN`, build (`android-release-build.yml`), Play internal, pre-release |
| `release.yml` | release/hotfix PR merged into `main`; dispatch | tag `vX.Y.Z`, delete the branch, promote, desktop installers, GitHub Release, back-merge PR |
| `play-rollout-ramp.yml` | daily 08:00 UTC; dispatch | raise the production fraction along the ramp |
| `play-promote.yml` | dispatch | `promote`, `rollout`, `halt`, `resume` |

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
only) another `release/*` branch is open. The branch starts with one commit:
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
4. Open the PR `release/x.y.z → main` and **merge with a merge commit**.
5. Approve the `production` deployment in the run. The ramp takes it from there;
   halt with `gh workflow run play-promote.yml -f action=halt`.
6. Merge the back-merge PR into `develop` **with a merge commit** (it is labelled
   `no-landing`; resolve conflicts on its `back-merge/vX.Y.Z` branch).

Production always gets the newest `vX.Y.Z-rcN`. If the branch moved after its
last RC, `release.yml` warns and still promotes the RC — push again to get a
new candidate first. A release with no RC fails at the tag step.

### Branch protection

Rulesets (applied by `scripts/release/owner-setup.sh`), admins may bypass:

- **main**: PR, 1 approval, merge commits only, checks `build`, `desktop`, `ios`,
  `Release tooling tests`, `PRs into main come from release/* or hotfix/*`,
  `Release notes are final`.
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
`RELEASE_APP_PRIVATE_KEY`; variable `RELEASE_APP_ID`). The train, the RC tags
and the back-merge PR use its token because anything done with `GITHUB_TOKEN`
starts no workflow: a train branch would never build an RC, and a back-merge
PR would never get CI. Without the App, the RC and release jobs fall back to
`GITHUB_TOKEN` (and the tag ruleset will refuse them), and the train fails.

### Shared build

`android-release-build.yml` (`workflow_call`) checks out full history
(`fetch-depth: 0`, needed for versionCode/versionName), decodes
`KEYSTORE_BASE64`, runs `:app:androidApp:bundleRelease` with the signing,
TMDB and Sentry secrets, uploads the AAB as an artifact and, for RCs, runs
`fastlane android internal`. `release.yml`'s dispatch path calls it without the
upload to exercise the build.

### Publishing to Google Play (ADR 0024, EPIC 34 / #77)

Wired in `android-release-build.yml` (`fastlane android internal`, from
`release-rc.yml`), `release.yml` (`android promote`), `play-rollout-ramp.yml`
(`android ramp`) and `play-promote.yml` (`promote`, `rollout`, `halt`,
`resume`). The ramp policy is `fastlane/play_rollout.rb`, tested by
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
  is a commit count nobody knows until the commit lands. The lane copies them
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
- **An RC uploads to the internal track only.** Production happens when the
  release branch merges into `main`, behind the `production` environment's
  approval, at 20% (ADR 0025).
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

`app/desktopApp/icons/{icon.icns,icon.ico,icon.png}` are a **placeholder**
generated programmatically (a flat rounded-square "play" glyph) — there's no
real Muviss brand artwork yet. Regenerating them from real artwork later is
a manual follow-up; nothing in the build depends on their content, only
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
matrix: `ubuntu-latest` → `packageDeb`, `macos-latest` → `packageDmg`,
`windows-latest` → `packageMsi`. Each format can only be produced on its native
OS (jpackage delegates to the OS's own packaging tool), which is why this is a
matrix of jobs rather than one job running three tasks, and `fail-fast: false`
keeps one broken leg from taking the other two down.

Windows/MSI used to be left out, on the grounds that it was the one leg nobody
here could verify locally before merging. EPIC 23 put it in: nobody can verify
it locally *either way*, and a CI run is better evidence than a reading of the
config. WiX ships preinstalled on `windows-latest` per `actions/runner-images`.
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

## 9. iOS (TestFlight / App Store) — EPIC 11 / issue #13

`app/iosApp` is a thin SwiftUI host embedding `:app:shared`'s Compose UI
(`ComposeUIViewController { MuvissApp() }`, `MainViewController.kt`). This
section covers what's automated vs. what's a manual, by-hand step — iOS has
no unattended CI signing path in this repo yet (see "Signing" below).

### Startup wiring

Unlike Android (`MuvissApplication.onCreate`, which runs before any
Activity), a SwiftUI app's `WindowGroup`/scene body is not guaranteed to run
on every launch — a background `BGAppRefreshTask` launch may never compose
it. So startup is split:

- **`AppDelegate.swift`** (`app/iosApp/iosApp/AppDelegate.swift`): a few
  lines, wired via `@UIApplicationDelegateAdaptor` in `iOSApp.swift`. Its
  `application(_:didFinishLaunchingWithOptions:)` is the one hook Apple
  guarantees runs before launch completes regardless of why the process
  launched, and it does exactly one thing: call `IosAppStartup.shared.start()`.
- **`IosAppStartup.kt`** (`app/shared/src/iosMain/.../ios/`): the real
  logic — starts crash reporting (`MuvissCrashReporting.start`, before Koin),
  then Koin (guarded via `KoinPlatformTools.defaultContext().getOrNull()`,
  the KMP-portable equivalent of `GlobalContext.getOrNull()`, which isn't
  exported on non-JVM targets),
  registers the `BGTaskScheduler` task, and configures `UNUserNotificationCenter`.
- **`MainViewController.kt`** additionally wires `IosNotificationCenter.pendingDeepLinkMediaId`
  into `MuvissApp(deepLinkMediaId, onDeepLinkConsumed)` — the same param pair
  Android's `MainActivity` feeds from a notification tap's Intent extra,
  just carried via a `StateFlow` instead since there's no Activity-recreation
  equivalent on iOS.

Sentry's DSN is baked in via the same generated `MuvissBuildConfig`
mechanism item 4 describes for Android — nothing iOS-specific to configure
beyond having `SENTRY_DSN` in `local.properties`/CI env when building.

### Notifications (EPIC 5 parity)

iOS has no WorkManager equivalent, so the Android
`NewEpisodesScheduler`/`NewEpisodesWorker`/`NewEpisodesNotifier` trio (see
item 4's sibling code in `app/androidApp/.../notifications/`) is mirrored
in Kotlin, in `app/shared/src/iosMain/.../ios/`, rather than in Swift:

- **`IosBackgroundRefresh.kt`**: `BGTaskScheduler`-based. There is no
  periodic-work primitive like `PeriodicWorkRequestBuilder` — every run is
  a one-shot `BGAppRefreshTaskRequest` that resubmits itself (~12h out,
  matching Android's repeat interval) after each run. Resolves
  `CollectionApi`/`SettingsApi` via `KoinComponent`, same reasoning as
  `NewEpisodesWorker`'s doc comment (the launch handler hands back a plain
  `BGTask`, nothing Koin can construct through).
- **`IosNotificationCenter.kt`**: `UNUserNotificationCenter`-based —
  requests permission (idempotent; the OS only prompts once), posts one
  notification per show (iOS groups same-`threadIdentifier` notifications
  into a stack itself, so no hand-built summary notification like
  Android's `InboxStyle` is needed), and its `UNUserNotificationCenterDelegateProtocol`
  implementation turns a tap into the pending deep-link id
  `MainViewController` reads.
- The global notifications toggle (`SettingsApi.observeNotificationsEnabled`)
  and per-show mute (already excluded inside
  `CollectionApi.refreshAndFindNewEpisodes`) are the same EPIC 5 checks
  Android's worker makes — no iOS-specific gating logic exists or should.

**Info.plist** (`app/iosApp/iosApp/Info.plist`) needs, and now has:
`BGTaskSchedulerPermittedIdentifiers` (`com.codingpit.muviss.refresh`, must
match `IosBackgroundRefresh`'s `TASK_ID` exactly) and `UIBackgroundModes` =
`fetch`. No `NSUserNotificationsUsageDescription`-style key is needed —
`UNUserNotificationCenter`'s permission prompt uses fixed system copy, not
an Info.plist string.

A Kotlin/Native interop note for anyone touching this code: `UNMutableNotificationContent`'s
`title`/`body`/`threadIdentifier`/`userInfo` bind as `val` (read-only) at
the property-reference site — Kotlin/Native's ObjC interop doesn't widen an
inherited read-only property to the subclass's read-write redeclaration —
so `IosNotificationCenter` sets them via `setValue(_, forKey:)` (KVC,
always available on `NSObject`) instead of `content.title = ...`.

### Versioning

Mirrors item 3's scheme (git-derived, no hardcoded version) via a **Run
Script build phase** named "Set version from git" (`project.pbxproj`,
after the Resources phase): it computes the same `versionCode`
(`git rev-list --count HEAD`) and a `versionName` (latest exact
`vMAJOR.MINOR.PATCH` tag, else `0.1.0-dev.<count>` — no `+sha` suffix
unlike Android's, since `CFBundleShortVersionString` is conventionally
kept to dotted numbers/simple dev suffixes) and overwrites
`CFBundleVersion`/`CFBundleShortVersionString` in the **built** Info.plist
via `/usr/libexec/PlistBuddy`. `Configuration/Config.xcconfig`'s
`CURRENT_PROJECT_VERSION`/`MARKETING_VERSION` are only the static fallback
used when git is unavailable (e.g. a source-only archive) — the script
no-ops (keeping them) if `git rev-list` fails.

### Bundle id / signing (manual — no CI signing path yet)

`Configuration/Config.xcconfig` sets `PRODUCT_NAME=Muviss` and
`PRODUCT_BUNDLE_IDENTIFIER=com.codingpit.muviss` — the same identifier as
`:app:androidApp`'s `applicationId`, one stable id across platforms rather
than the template's original per-developer
`com.codingpit.muviss.KotlinProject$(TEAM_ID)` suffix. `CODE_SIGN_STYLE =
Automatic` (project.pbxproj) plus a blank `TEAM_ID` xcconfig variable means:

1. Open `app/iosApp/iosApp.xcodeproj` in Xcode.
2. Signing & Capabilities → pick your Apple Developer team (free personal
   team works for a Simulator/device run; TestFlight needs a paid Apple
   Developer Program membership, $99/yr).
3. Either let Xcode fill `DEVELOPMENT_TEAM` automatically, or put your
   Team ID in `Configuration/Config.xcconfig`'s `TEAM_ID` (gitignored-safe
   to edit locally; don't commit a real team id — there isn't one to
   commit today).
4. Product → Archive (Release configuration) once signing resolves.
5. Organizer → Distribute App → TestFlight & App Store → upload. This step
   requires a real, paid Apple Developer account and was **not**
   attempted here — no such account exists in this environment.
6. App Store Connect → TestFlight tab: add internal testers, submit for
   Beta App Review if using external testing.
7. App Store listing: reuse `docs/store/LISTING.md`'s copy (see its new
   "App Store (iOS)" section for the App-Store-specific deltas — subtitle,
   keywords, screenshot sizes) and `docs/PRIVACY.md` for the App Store
   "Privacy Nutrition Label" — same "no accounts, no backend, no
   analytics" story `docs/store/DATA_SAFETY.md` gives Play, just answered
   in App Store Connect's own questionnaire shape instead of a repo file
   (there is no App Store Connect API-driven equivalent wired up).

### App icon — gap found, not fixed here

`Assets.xcassets/AppIcon.appiconset` has one real image
(`app-icon-1024.png`, correctly 1024×1024) plus two *declared-but-empty*
slots for iOS 18's dark/tinted Home Screen icon appearances (no file
assigned — falls back to the default icon on iOS 18+, a cosmetic gap
only). The blocking one: **`app-icon-1024.png` has an alpha channel**
(`sips -g hasAlpha` → `yes`). App Store Connect rejects an App Store/
marketing icon with transparency (`Invalid Large App Icon` at Archive
validation or upload). Fix before the first real submission by
flattening it onto an opaque background (e.g.
`sips -s format png --setProperty hasAlpha no ...` after compositing
onto white/black, or re-export from the source design tool without
alpha) — not attempted here since there's no real Muviss brand artwork to
re-derive it from yet (same caveat item 7 gives the desktop icons).

### Sentry test crash

The trigger ships in every build: **Settings → About, tap the version 7 times**
and a "Send test crash" row appears, which throws an uncaught
`MuvissTestCrash` (search Sentry for that name). It is in release builds on
purpose — the point is a crash from the exact artefact the store serves,
because only that proves the mapping uploaded with it matches.

Run against the release artefacts, with the Sentry secrets set (section 4):

1. **Android** (gates v1.0.0, #81): the `release.yml` log shows the R8 mapping
   uploaded. Install from the Play internal track, trigger, relaunch (the SDK
   sends a fatal crash on the next start). The event has
   `environment=production`, release `com.codingpit.muviss@<versionName>+<versionCode>`,
   and a **deobfuscated** Kotlin stack.
2. **Opt-out**: Settings → Privacy → "Send crash reports" off, trigger again,
   relaunch. No new event.
3. **Desktop** (gates v1.0.0): install the packaged installer from the GitHub
   release, trigger, confirm the event with the right release and environment.
   No mapping applies — the desktop build is not obfuscated.
4. **iOS**: rides EPIC 35's TestFlight check (#78), which also owns uploading
   dSYMs for the app and `Shared.framework`; without them a `Shared` frame is
   a raw address.

**Not checked by hand: a crash in `NewEpisodesWorker` with the app not
running** (#68 asked for it). What makes that case report is start order —
`MuvissApplication.onCreate` calls `MuvissCrashReporting.start` before anything
else, and a worker cold start runs `onCreate` too — which is the same code path
the gesture exercises. Proving it for real would need a crash hook in the
worker in release code; that was judged not worth it.

### Simulator run — verified 2026-09-02

The app builds, installs, launches and works on the Simulator. What it took,
because none of it was obvious and the previous pass's notes here were wrong
by the time anyone read them again:

- **The old blocker is gone.** EPIC 11's pass recorded that only the iOS 18.1
  Simulator runtime was installed, that `xcodebuild -scheme … -destination …`
  therefore resolved no Simulator destination, and that the asset catalog's
  app-thinning step failed with `No simulator runtime version from ["22B81"]
  available to use with iphonesimulator SDK version 23C53`. All three were
  purely environmental. With an iOS 26.x runtime installed (Xcode → Settings →
  Components) `-destination` resolves normally and thinning passes. Create a
  device if none exists on it:

  ```bash
  xcrun simctl create Muviss-iOS26 \
    com.apple.CoreSimulator.SimDeviceType.iPhone-16-Pro \
    com.apple.CoreSimulator.SimRuntime.iOS-26-3
  ```

- **The real blocker was the link, and it had never been reached.** `Shared`
  is a **static** framework (`isStatic = true`, `app/shared/build.gradle.kts`),
  so every symbol its Kotlin/Native cinterops leave unresolved lands on
  whoever links it — and a cinterop's `linkerOpts` reach only the binaries
  *Gradle* links, never Xcode's. Two sets were missing, 50 symbols in total:
  `_OBJC_CLASS_$_Sentry*` (`io.sentry:sentry-kotlin-multiplatform`, via
  `:core:common`) and `_sqlite3_*` (`co.touchlab:sqliter`, via
  `:core:database`). Both Xcode targets now set `SENTRY_XCFRAMEWORK_SLICE`
  (SDK-conditional, pointing at the xcframework the root `build.gradle.kts`
  already unzips) and link `-framework Sentry -lsqlite3`; the app target also
  has an **Embed Sentry.framework** script phase, because Sentry-Dynamic is
  dynamic and the app would otherwise die at launch with `dyld: Library not
  loaded: @rpath/Sentry.framework/Sentry`. The extension finds the same copy
  through its `@executable_path/../../Frameworks` rpath.

  A script phase rather than a Copy Files phase: the slice differs between
  device and simulator, and a `PBXFileReference` cannot switch on SDK.

- **`ci.yml` now has a `macos-latest` `ios` job** doing exactly this —
  `linkDebugFrameworkIosSimulatorArm64` then `xcodebuild -sdk iphonesimulator
  CODE_SIGNING_ALLOWED=NO`. It exists because the gap above survived two
  months and three epics with CI green throughout: everything else runs on
  Linux, and `allMetadataJar` compiles `iosMain` to a metadata klib without
  ever invoking Kotlin/Native or Xcode.

- **Signing.** A Simulator build needs no team; `TEAM_ID` stays blank and
  `CODE_SIGNING_ALLOWED=NO` covers CI.

- **The App Group works on the Simulator with no team at all** — worth knowing,
  because §11 reads as though it could not. `muviss.db` was created at
  `.../data/Containers/Shared/AppGroup/<uuid>/muviss.db`, not the
  `NSDocumentDirectory` fallback: the Simulator honours the entitlement
  without a provisioning profile. So the fallback in `DatabaseFactory.ios.kt`
  is exercised on *device* builds without a team, not here. Don't read a
  passing Simulator run as evidence that the fallback works.

- **OAuth's PKCE flow state expires, and the failure is silent and
  misleading.** A first attempt was abandoned mid-login and finished about
  ten minutes later; GoTrue could no longer match the flow state, fell back
  to `site_url` (`http://localhost:3000`), and Safari showed "cannot connect
  to the server". Nothing was wrong with the app, the scheme or the
  allow-list — `uri_allow_list` on the live project reads
  `muviss://auth-callback`, exactly as `supabase/config.toml` declares. The
  retry, finished in seconds, redirected to the custom scheme and completed.
  If sign-in ever lands on `localhost`, suspect a slow login before
  suspecting configuration.

- **The `muviss` URL scheme is registered** (`CFBundleURLTypes` in
  `app/iosApp/iosApp/Info.plist`) and `iOSApp.swift`'s `.onOpenURL` hands
  every incoming URL to `IosDeepLinks.handle`. That makes both the widget's
  `muviss://title/<id>` and OAuth's `muviss://auth-callback` live, and
  `Pkce.ios.kt`'s `sha256`/`secureRandomBytes` are now real
  (CommonCrypto + `SecRandomCopyBytes`). Its old comment claimed iOS sign-in
  needed `ASWebAuthenticationSession`; it does not — `ProfileScreen` opens
  the authorize URL through Compose's `LocalUriHandler`, which on iOS is
  `UIApplication.openURL`. ASWebAuthenticationSession remains a UX
  improvement, not a requirement.

- **App icon.** `app-icon-1024.png` no longer has an alpha channel (it was
  RGBA but fully opaque, so it was re-encoded as RGB with no pixel changed),
  which clears the `Invalid Large App Icon` rejection at Archive validation.
  The two declared-but-empty dark/tinted slots, and the fact that the artwork
  is still the KMP template's blue rather than anything in `MuvissPalette`,
  are open.

- **Smoke test, all passing** on iOS 26.3: launch with no dyld error; TMDB
  search and the Popular rows (Ktor on Kotlin/Native); add to library, tick
  two episodes, and both `episodeProgress.seen` and the derived
  `episodePlay` id `episodeId@watchedAtEpochMs` present in SQLite (ADR
  0011/0013); state surviving a cold start; the Progress tab deriving the
  right next episode; `simctl openurl muviss://title/tmdb:tv:1399` landing on
  that title's detail; and a full GitHub sign-in round trip — session
  persisted, then push and pull, with local and server row counts matching
  across all six tables.

- **Not attempted here**, both needing a real paid Apple Developer account:
  Archive → TestFlight upload, and the Sentry test crash on a signed device
  build. The `MuvissWidget` target now *links* (it needed the same Sentry and
  sqlite3 settings), but nothing about the widget itself has been run.

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
all derived from `app/desktopApp/icons/icon.png` via `sips` (same
placeholder-art caveat item 7 gives the desktop icons — there's no real
Muviss brand artwork yet, so regenerating these from real artwork later is
a manual follow-up, same as the desktop ones). `theme_color`/`background_color`
in the manifest and the `<meta name="theme-color">` in `index.html` match
the app's actual Material theme (`Purple = 0xFF6C5CE7`, `core/designsystem/.../Theme.kt`),
not a placeholder color. Verified installable in Chrome (the manifest
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

1. **Fill in `TEAM_ID`** in `app/iosApp/Configuration/Config.xcconfig` (Xcode
   > Signing & Capabilities shows it once a team is selected). Same value the
   rest of §9 needs; it is gitignored-by-convention only in the sense that it
   ships blank — do not commit a real one.
2. **Register the App Group** `group.com.codingpit.muviss` in the Apple
   Developer portal (Certificates, Identifiers & Profiles > Identifiers > App
   Groups).
3. **Enable it on both bundle ids** — `com.codingpit.muviss` and
   `com.codingpit.muviss.MuvissWidget`. Both entitlement files
   (`app/iosApp/iosApp/iosApp.entitlements`,
   `app/iosApp/MuvissWidget/MuvissWidget.entitlements`) already declare it;
   the portal has to agree or neither target will sign.
4. **Let Xcode regenerate the provisioning profiles** (automatic signing
   picks the change up on the next build).

The identifier appears in four places and they must all match: the two
entitlement files, `MUVISS_APP_GROUP` in
`core/database/src/iosMain/.../DatabaseFactory.ios.kt`, and the
`UserDefaults(suiteName:)` in `app/iosApp/MuvissWidget/WidgetIntents.swift`.

### iOS: what to check in Xcode

The `MuvissWidget` target was added to `project.pbxproj` by hand — the
project opens and its target graph parses, but **it has never been built**,
because building it needs Xcode and a team. Expect to adjust:

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
