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
