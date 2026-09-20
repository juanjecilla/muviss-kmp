# Muviss

A personal, offline-first tracker for **movies and TV shows**, built with Kotlin Multiplatform + Compose Multiplatform. Search titles, save them to your collection, and track episode/watch progress — on Android (primary), iOS, Desktop, and Web, from one codebase.

> User-focused, no social features. Data comes from [TMDB](https://www.themoviedb.org/) behind a pluggable source abstraction.

## Features (this pass)

- **Search & discovery** — debounced TMDB search + weekly trending, movie/TV detail with seasons & episodes. *(fully implemented — the reference vertical slice)*
- **Collection, Progress, Profile, Settings** — scaffolded slices, filled in per the [epics](docs/EPICS.md).

## Architecture

Vertical slice per feature, each split into `:api` / `:domain` / `:data` / `:ui` (clean architecture), wired with **Koin** and **Navigation Compose**. Offline-first with **SQLDelight** as the source of truth; optional cloud sync is deferred behind a `SyncEngine`. See **[CLAUDE.md](CLAUDE.md)** for the module map, **[CONTEXT.md](CONTEXT.md)** for the domain glossary, **[docs/adr/](docs/adr/)** for decisions, and **[AGENTS.md](AGENTS.md)** for the working agreement if you are an agent.

## Getting started

1. **TMDB credentials** — from https://www.themoviedb.org/settings/api, add to `local.properties` (gitignored). Either works; set both if you have both:
   ```properties
   # v4 "API Read Access Token" — preferred. Sent as `Authorization: Bearer`, so it never appears in a URL.
   TMDB_READ_TOKEN=your_v4_token_here
   # v3 API key — the fallback when no read token is set. Sent as the `api_key` query parameter.
   TMDB_API_KEY=your_v3_key_here
   ```
   Both are baked into the binary (ADR 0007), and both can also come from same-named environment variables.
2. **Enable the pre-commit hook** (once per clone):
   ```bash
   git config core.hooksPath .githooks
   ```

## Running

- **Android**: `./gradlew :app:androidApp:assembleDebug` (or run from the IDE)
- **Desktop**: `./gradlew :app:desktopApp:run`
- **Web (Wasm)**: `./gradlew :app:webApp:wasmJsBrowserDevelopmentRun`
- **iOS**: open `app/iosApp` in Xcode and run

## Testing & quality

```bash
./gradlew :feature:search:ui:jvmTest :feature:search:data:jvmTest :core:model:jvmTest
./gradlew spotlessApply        # format
./gradlew spotlessCheck detekt # verify (also run in CI + pre-commit)
```

## Tech

Kotlin 2.4 · Compose Multiplatform 1.11 · AGP 9 / Gradle 9.1 · Koin · SQLDelight · Ktor client · Coil3 · Navigation Compose · Spotless (ktlint) + Detekt.
