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

## 6. OSS attribution / license report — deferred

The epic asked for an OSS-attribution screen fed by a license-report
Gradle plugin. `com.github.jk1.dependency-license-report` (3.1.4, the
current release) was tried and dropped: its report task calls
`Task.project` at execution time and isn't
configuration-cache-compatible — `generateLicenseReport` failed with
"cannot serialize object of type `DefaultProject`" against this project's
`org.gradle.configuration-cache=true` setting. Rather than disable the
configuration cache project-wide (or fork the plugin) to accommodate one
report task, this is deferred.

**Follow-up for EPIC 8** (which owns the Settings/About screen this would
feed): re-evaluate license-report tooling then — either a
configuration-cache-friendly plugin if one exists by then, or a small
hand-rolled Gradle task that walks `runtimeClasspath` POM metadata (similar
in spirit to the ~15-line `MuvissBuildConfig` generator this repo already
uses instead of BuildKonfig, see ADR 0007). Until then, `docs/PRIVACY.md`
and the TMDB attribution constant in
`feature/settings/domain/.../TmdbAttribution.kt` (with its own
`TODO(EPIC-8)`) are the source of truth for attribution text.

## 7. Manual smoke test before a real release

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
