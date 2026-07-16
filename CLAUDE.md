# CLAUDE.md

Guidance for Claude Code (and humans) working in the Muviss repo.

## What this is

Muviss is a Kotlin Multiplatform + Compose Multiplatform tracker for movies and TV shows. Package root `com.codingpit.muviss`. Targets: **Android (primary)**, iOS, Desktop (JVM), Web (JS + Wasm), plus a dormant Ktor `:server`. Offline-first, user-focused, no social. Read `CONTEXT.md` for the domain glossary and `docs/adr/` for why things are the way they are.

## Architecture (see ADR 0004)

Vertical slice per feature, each split into four modules:

```
:feature:<name>:api      public contract — the ONLY module peers may depend on
:feature:<name>:domain   use cases + repository interfaces (internal)
:feature:<name>:data     repo impls + data sources + Koin module (internal)
:feature:<name>:ui       Compose screens + ViewModels + nav (internal)
```

Dependency rule: **`ui → domain ← data`**; cross-feature deps go through the peer's `:api` only. Shared infra:

```
:models             common exported data models (MediaId, MediaSummary, WatchStatus…)
:core:common        AppDispatchers, Koin, coroutines
:core:model         WatchProgress + WatchStatusCalculator (status derivation)
:core:database      SQLDelight schema + drivers (expect/actual)
:core:network       Ktor client + MetadataProvider + TmdbProvider
:core:designsystem  Compose theme + shared components (PosterImage, MuvissIcons)
:app:shared         app shell: MuvissApp() — Koin start, theme, NavHost, bottom bar
:app:{androidApp,desktopApp,webApp,iosApp}   thin platform hosts
:server             Ktor, dormant (not deployed)
```

DI is **Koin**. Each module contributes a Koin module; `app/shared/.../di/AppModules.kt` assembles them. Navigation is the official **Navigation Compose** with `@Serializable` type-safe routes; each feature `:ui` exposes a `NavGraphBuilder.<name>Section()` extension and a `<Name>Route`.

Build boilerplate is in the included build `build-logic/`: apply `id("muviss.kmp.library")` (non-UI modules) or `id("muviss.kmp.compose")` (UI modules). The Android namespace is derived from the module path automatically.

## Adding a feature (copy the search slice)

1. Create `:feature:<name>:{api,domain,data,ui}` mirroring `feature/search/*` (search, collection, progress, and settings are real; `profile` is still a stub).
2. Register the modules in `settings.gradle.kts` (the `listOf(...)` loop) and add the feature name.
3. Add the feature's Koin modules to `AppModules.kt`, and its `Route` + `Section` to `MuvissApp.kt`.
4. Depend on a peer only via its `:api`.

## Domain rules that bite (see ADR 0005)

`WatchProgress` (episode ticks) is the source of truth; `WatchStatus` is **derived** by `WatchStatusCalculator` — never store status independently. TV: NotStarted → Watching → Watched (all aired, ongoing) → Finished (all seen, ended). Movies: NotStarted → Watched. `Favorite` is an independent flag.

## Commands

```bash
./gradlew :app:androidApp:assembleDebug        # Android (primary)
./gradlew :app:desktopApp:run                  # Desktop
./gradlew :app:webApp:wasmJsBrowserDevelopmentRun   # Web (Wasm)
./gradlew :app:shared:jvmTest                  # shared tests on JVM
./gradlew :feature:search:ui:jvmTest           # a module's tests
./gradlew spotlessApply                        # auto-format
./gradlew spotlessCheck detekt                 # what the pre-commit hook runs
```

iOS: open `app/iosApp` in Xcode. Enable the pre-commit hook once per clone: `git config core.hooksPath .githooks`.

## Setup gotchas

- **TMDB key**: put `TMDB_API_KEY=<v3 key>` in `local.properties` (gitignored). It is baked into the generated `MuvissBuildConfig` (see ADR 0007). Without it, search returns errors at runtime but everything still builds.
- **Release signing / Sentry**: `RELEASE_STORE_FILE`/`RELEASE_STORE_PASSWORD`/`RELEASE_KEY_ALIAS`/`RELEASE_KEY_PASSWORD` and `SENTRY_DSN` are all optional, read from `local.properties` or env vars the same way as the TMDB key. Without them, `:app:androidApp:assembleRelease` falls back to debug signing and crash reporting no-ops. See `docs/RELEASING.md`.
- **Desktop DB**: `:core:database`'s JVM driver is file-backed (`DatabaseFactory.jvm.kt`), one SQLite file at the OS-appropriate app-data dir — `~/Library/Application Support/Muviss` (macOS), `%APPDATA%\Muviss` (Windows), `$XDG_DATA_HOME/Muviss` or `~/.local/share/Muviss` (Linux) — created on first run, opened and migrated forward on later ones. jvmTest suites bypass it entirely (they build `JdbcSqliteDriver.IN_MEMORY` + `Schema.create` directly); `DatabaseFactoryJvmTest` covers the file/migrate-on-open logic itself against a temp dir.
- **Migrations**: the schema (`AppSettings.sq` / `CollectionEntry.sq` / `EpisodeProgress.sq` / `Profile.sq`) grew additively with no `.sqm` files through EPICs 0-8 — every install so far always created the DB fresh, there was never an upgrade path. Those four files **as they stand today are schema version 1**, baselined with no migration file needed to represent it (see `core/database/build.gradle.kts`). From here on, any change to a `CREATE TABLE` that existing installs need to pick up without losing data ships as a new `<version>.sqm` file alongside the `.sq` change. `verifyMigrations` is on, wired into `:core:database:check` (and CI) automatically by the SQLDelight plugin — it fails the build if the `.sqm` chain doesn't reproduce what the `.sq` files declare, against the checked-in schema fixture `core/database/src/commonMain/sqldelight/databases/1.db` (regenerate it via `generateCommonMainMuvissDatabaseSchema` if you ever need to, though normally you'd add a `2.db` for the next version instead of touching this one).
- **Web + DB**: `:core:database`'s JS/Wasm driver (`DatabaseFactory.web.kt`, shared via `src/webMain`) persists for real now (EPIC 13) — SQLDelight's `web-worker-driver` running `@cashapp/sqldelight-sqljs-worker` (SQL.js in a Web Worker). `generateAsync` is on schema-wide; every generated mutation is `suspend` on every platform, Android/iOS/JVM bridge back to their sync drivers via `MuvissDatabase.Schema.synchronous()` (from `app.cash.sqldelight:async-extensions`) so nothing about their behavior changed. Web persistence is **session-only** — SQL.js keeps the DB in the worker's memory, no OPFS/IndexedDB backing yet, so a page reload starts empty. Getting the worker to actually load required two webpack fixes documented in `docs/adr/0008-...md`'s 2026-07-16 amendment (`app/webApp/webpack.config.d/copy-sqljs-wasm.js`): the `new Worker(new URL(...))` construction has to be one untouched `js(...)` string or webpack's native worker-chunking never triggers, and `sql.js`'s dual Node/browser loader needs `fs`/`path`/`crypto` resolve fallbacks disabled.
- **Data export**: real per-platform impls — Android's system share sheet, iOS's `UIActivityViewController` (writes to a temp file first so the receiving app sees a proper `.json`), JVM's native `FileDialog` (SAVE mode), web's `<a download>` + object-URL Blob (`DataExporter.js.kt` / `DataExporter.wasmJs.kt` — can't share one `webMain` file because `org.w3c.files.Blob`'s Kotlin interop type differs between the two: `Array<Any?>` on `js`, `JsArray<JsAny?>` on `wasmJs`). Now that web persistence is real (see above), web's exporter has actual data to hand off too.
- **Bottom-nav icons**: `material-icons-extended` still isn't published for this Compose version — the five nav glyphs are hand-bundled `ImageVector`s in `:core:designsystem/.../icon/MuvissIcons.kt` (parsed from Material Design SVG path data with `PathParser`) rather than pulled from that artifact.
- **OSS licenses**: `app.cash.licensee` (applied to `:app:androidApp` only) validates dependency licenses at build time and is config-cache-compatible; the Settings > About > Licenses screen instead reads a generated-once, checked-in list (`feature/settings/domain/.../OssLicenses.kt`) — see docs/RELEASING.md item 6 for why the two aren't wired together live.
- Bleeding-edge toolchain (Kotlin 2.4, AGP 9, Gradle 9.1). Prefer editing `gradle/libs.versions.toml` for versions; verify new libs publish for `wasm-js` before adding them.
- **`:models`'s serialization dependency is `api`, not `implementation`** (see `models/build.gradle.kts`) — it must stay that way. `:models`'s `@Serializable` enums (`MediaType`, `WatchStatus`, `ProductionStatus`...) expose their generated `Companion` as a `KSerializer` supertype; the JVM backend tolerates a consumer not having `kotlinx-serialization-core` on its own compile classpath (lazy class-file resolution), but the JS/Wasm klib backend fully resolves that supertype at compile time for every downstream module, so scoping it to `implementation` breaks `compileKotlinJs`/`compileKotlinWasmJs` for any module that merely references one of these enums (e.g. `:core:model`'s `WatchStatusCalculator` did — this was issue #10).
