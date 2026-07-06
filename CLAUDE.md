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
:core:designsystem  Compose theme + shared components (PosterImage)
:app:shared         app shell: MuvissApp() — Koin start, theme, NavHost, bottom bar
:app:{androidApp,desktopApp,webApp,iosApp}   thin platform hosts
:server             Ktor, dormant (not deployed)
```

DI is **Koin**. Each module contributes a Koin module; `app/shared/.../di/AppModules.kt` assembles them. Navigation is the official **Navigation Compose** with `@Serializable` type-safe routes; each feature `:ui` exposes a `NavGraphBuilder.<name>Section()` extension and a `<Name>Route`.

Build boilerplate is in the included build `build-logic/`: apply `id("muviss.kmp.library")` (non-UI modules) or `id("muviss.kmp.compose")` (UI modules). The Android namespace is derived from the module path automatically.

## Adding a feature (copy the search slice)

1. Create `:feature:<name>:{api,domain,data,ui}` mirroring `feature/search/*` (search `:ui`/`:data` are the real reference; the other slices are stubs).
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
- **Web + DB**: `:core:database` has no JS/Wasm driver yet (factories throw); collection/progress are not wired on web.
- **Bottom-nav icons**: label-only for now — `material-icons` isn't published for this Compose version.
- Bleeding-edge toolchain (Kotlin 2.4, AGP 9, Gradle 9.1). Prefer editing `gradle/libs.versions.toml` for versions; verify new libs publish for `wasm-js` before adding them.
