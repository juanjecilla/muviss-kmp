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
  `.github/workflows/release.yml`.
  ```bash
  base64 -i release.keystore.jks | pbcopy   # macOS; use -w0 on Linux
  ```

When none of the four values are present — the default for a fresh clone —
`app/androidApp/build.gradle.kts` falls back to **debug signing** for the
`release` build type, so `assembleRelease`/`bundleRelease` still work for
every contributor; they just don't produce a Play-Store-installable
artifact. Both paths are exercised as part of this epic's verification (a
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

To cut a release, tag it — nothing else to bump:

```bash
git tag v1.0.0
git push origin v1.0.0   # triggers .github/workflows/release.yml
```

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

- **android / iOS / jvm**: real actuals call `Sentry.init`/`captureException`.
- **js / wasmJs**: no-op actuals. The Sentry KMP artifact does publish
  (stubbed) `js`/`wasmJs` variants, but this project scopes the Gradle
  dependency to `androidMain`/`iosMain`/`jvmMain` only (see
  `core/common/build.gradle.kts`) so the web targets never resolve it at
  all — one less unknown on an already-unsupported platform (`:core:database`
  has no web driver either, see CLAUDE.md's setup gotchas).

The DSN is baked in via a generated `MuvissBuildConfig` in `:app:shared`,
the same mechanism ADR 0007 describes for the TMDB key: it reads
`SENTRY_DSN` from `local.properties` or the environment, defaulting to `""`.
`CrashReporter.init("")` no-ops, so building/running without a DSN (the
default for every contributor and most CI runs) never touches Sentry.

To enable it locally: add `SENTRY_DSN=https://<key>@o<org>.ingest.sentry.io/<project>`
to `local.properties`. In CI: set the `SENTRY_DSN` repository secret (see
`.github/workflows/release.yml`).

`MuvissApp()` calls `CrashReporter.init(MuvissBuildConfig.SENTRY_DSN)` once
at startup, inside a `remember {}` alongside the existing image-loader setup.

## 5. CI release job

`.github/workflows/release.yml` triggers on `v*` tags, checks out full git
history (`fetch-depth: 0` — needed for accurate versionCode/versionName),
decodes `KEYSTORE_BASE64` to a temp file, runs
`:app:androidApp:bundleRelease` with the signing/DSN/TMDB secrets as env
vars, and uploads the resulting `.aab` as a workflow artifact. There is no
Play publishing step yet — the first release is uploaded to the Play
Console by hand. The existing `ci.yml` (push/PR to `main`) is untouched.

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

`nativeDistributions { modules(...) }` controls which JDK modules `jlink`
keeps in the bundled runtime image — anything omitted is stripped, and a
missing module surfaces as a runtime `NoClassDefFoundError`/`module not
found`, not a build failure, so guessing this list is risky. The list
actually shipping (`java.desktop`, `java.instrument`, `java.management`,
`java.sql`, `jdk.unsupported`) was derived by building the uber jar and
asking `jdeps` what it actually touches, not guessed:

```bash
./gradlew :app:desktopApp:packageUberJarForCurrentOS
jdeps --print-module-deps --ignore-missing-deps \
  app/desktopApp/build/compose/jars/com.codingpit.muviss-*.jar
```

`java.sql` is the one worth calling out by name: it's what `:core:database`'s
`sqlite-jdbc` driver (`DatabaseFactory.jvm.kt`) needs, and nothing in
application code imports `java.sql` directly, so it's easy to drop by
mistake if this list is ever hand-edited instead of re-derived.

### App icon

`app/desktopApp/icons/{icon.icns,icon.ico,icon.png}` are a **placeholder**
generated programmatically (a flat rounded-square "play" glyph) — there's no
real Muviss brand artwork yet. Regenerating them from real artwork later is
a manual follow-up; nothing in the build depends on their content, only
their presence/format (`.icns` for `macOS { iconFile }`, `.ico` for
`windows { iconFile }`, `.png` for `linux { iconFile }`).

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
exercises them (see below); their Gradle configuration is reviewed for
correctness but is otherwise **untested** until a matching-OS run happens.

### CI

`.github/workflows/release.yml`'s `desktop-release` job runs a 2-entry OS
matrix on the same `v*` tag trigger as the Android `release` job:
`ubuntu-latest` → `packageDeb`, `macos-latest` → `packageDmg`. Each format
can only be produced on its native OS (jpackage delegates to the OS's own
packaging tool), which is why this is a matrix of jobs rather than one job
running three tasks. Windows/MSI is **not** in the matrix: it would be the
one desktop CI leg nobody on this project can verify locally before merging
it (no Windows/macOS-cross-build path, no Windows machine in hand), so it's
left as a follow-up rather than shipped untested and possibly silently
broken on every future tag. (GitHub's `windows-latest` runner image does
list the WiX Toolset as preinstalled per `actions/runner-images`, which is
what `packageMsi` needs — so adding the third matrix entry later is likely
just copying the `ubuntu-latest` entry's shape with `os: windows-latest`,
`task: :app:desktopApp:packageMsi`, and the MSI output path — but "likely"
isn't "verified," hence leaving it out for now.)

Like the Android `release` job, there's no GitHub Release object created —
installers upload as workflow artifacts (`muviss-desktop-<os>-<tag>`), same
as the AAB. Attaching to an actual GitHub Release (e.g. via
`softprops/action-gh-release`) is a natural follow-up once one exists.

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
  logic — starts Koin (guarded via `KoinPlatformTools.defaultContext().getOrNull()`,
  the KMP-portable equivalent of `GlobalContext.getOrNull()`, which isn't
  exported on non-JVM targets), calls `CrashReporter.init(MuvissBuildConfig.SENTRY_DSN)`,
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

### Sentry test crash — manual, not attempted

Needs a live `SENTRY_DSN` (see item 4) and a physical build; not exercised
in this environment (no DSN configured, no Apple Developer account to
sign a device build with). To verify once a DSN exists: force a crash
(e.g. a debug-only button calling `fatalError()` or throwing an
uncaught Kotlin exception across the `CrashReporter` seam) on a signed
device/TestFlight build, and confirm it shows up in the Sentry project
within a few minutes — same check item 8 describes for Android.

### Environment notes from this epic's verification pass

This environment has Xcode 26.2 with only the iOS 18.1 Simulator runtime
installed (Xcode 26.2's SDK is iOS 26.2; the matching Simulator runtime is
an ~8.4 GB download via Settings → Platforms, not fetched here per this
epic's "don't fight missing tooling" scope). Two consequences, both purely
environmental — not project defects:

- `xcodebuild -scheme iosApp -destination ...` never resolves *any*
  Simulator destination in this sandbox, even pinned to the installed
  18.1 runtime with a real checked-in shared scheme
  (`xcshareddata/xcschemes/iosApp.xcscheme`, added as part of this epic)
  and `IPHONEOS_DEPLOYMENT_TARGET` lowered from the template's 18.2 to
  18.0 (also kept — a reasonable, harmless widening of device
  compatibility on its own merits). `xcodebuild -target iosApp -sdk
  iphonesimulator ...` **does** work and is what this epic's Kotlin+Swift
  verification used; a real Xcode.app GUI run (not exercised here — no
  windowed session) very likely isn't affected, since Xcode's own Run
  button uses a different destination-discovery path than headless
  `xcodebuild -scheme`.
- Even via `-target`, asset-catalog compilation's app-thinning step
  (`CompileAssetCatalogVariant thinned`) fails with `No simulator runtime
  version from ["22B81"] available to use with iphonesimulator SDK version
  23C53` — the installed 18.1 runtime can't satisfy Xcode 26.2's app-
  thinning validation for its own 26.2 SDK. Everything before that step —
  Kotlin compilation (`:app:shared:compileKotlinIosSimulatorArm64`/
  `compileKotlinIosArm64`), `linkDebugFrameworkIosSimulatorArm64`,
  `embedAndSignAppleFrameworkForXcode`, and Swift compilation of
  `AppDelegate.swift`/`iOSApp.swift`/`ContentView.swift` against the
  exported `Shared` framework — succeeds cleanly. A real simulator run
  (`xcrun simctl boot` + install + launch) needs that ~8.4 GB runtime
  download and was not attempted.

## 10. Web (GitHub Pages, PWA) — EPIC 13 / issue #16

### Deploy workflow

`.github/workflows/deploy-pages.yml` builds `:app:webApp:wasmJsBrowserDistribution`
and deploys `app/webApp/build/dist/wasmJs/productionExecutable` to GitHub
Pages via the standard `actions/configure-pages` → `actions/upload-pages-artifact`
→ `actions/deploy-pages` trio, on every push to `main` (plus manual
`workflow_dispatch`). Wasm was chosen over the `js` target as the deployed
build — it's the one CLAUDE.md documents as primary (`wasmJsBrowserDevelopmentRun`
is the documented dev command) and the one exercised end-to-end during this
epic's verification (see the ADR 0008 amendment for the live-browser check).

**This repo is private, and GitHub Pages on a private repo needs a paid
plan** — confirmed directly, not assumed:

```bash
gh api -X POST repos/{owner}/{repo}/pages -f "build_type=workflow"
# → 422 "Your current plan does not support GitHub Pages for this repository."
```

The workflow is committed and ready to run the moment either of these
happens (no code changes needed, just flip the switch):

1. **Make the repo public** (Settings → General → Danger Zone → Change
   visibility), or
2. **Upgrade to GitHub Pro/Team/Enterprise** (any paid plan enables Pages on
   private repos).

Then, one-time, either let the first push to `main` after that trigger the
workflow (it self-configures Pages via `actions/configure-pages` — no
separate manual "enable Pages" click needed once the plan/visibility allows
it), or do it explicitly first: **Settings → Pages → Build and deployment
→ Source → GitHub Actions**.

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
but it is **session-only** (in the worker's memory; a page reload starts
from an empty database again, there is no OPFS/IndexedDB-backed durability
yet). Getting the worker to actually load in a webpack-bundled build needed
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
5. Reload the page; confirm the Settings screen goes back to defaults —
   this is *expected* today (session-only persistence, see above), not a
   bug to chase.

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
