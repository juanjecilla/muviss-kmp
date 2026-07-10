# Muviss — Epics

Roadmap to production (v1 = **Android, Play Store, local-only**). Each epic below E1 maps to one GitHub issue and is scoped so a single agent can implement it end-to-end. Benchmark: [TV Time](https://www.tvtime.com) feature set, minus social/community (Muviss is user-focused, no social — see `CLAUDE.md`).

## TV Time parity map

| TV Time feature | Muviss v1? | Epic |
|---|---|---|
| Track everything you watch (episode check-off) | Yes | E2, E3 |
| Watchlist / "watch next" | Yes | E2, E3 |
| Discover / trending / genres | Search + trending done; discover browse pending | E6 |
| New-episode notifications | Yes (local notifications, Android) | E5 |
| Viewing stats (episodes, hours, genres) | Yes (computed locally) | E4 |
| Where to watch (streaming availability) | Yes — TMDB watch/providers | E6 |
| Community / social / comments | **No — excluded by product principle** | — |
| Accounts + cloud sync | Post-v1 | E9 |

## Dependency graph

```
E2 Collection ──► E3 Progress ──► E4 Stats     E7 Release Eng. (parallel from day 1)
      │                └────────► E5 Notifications
      └─────────────────────────► E6 Discovery+Providers (parallel after E2)
E8 Settings+Polish (parallel, light deps on E2/E3)          E9 Sync (post-v1)
```

| Wave | Parallel epics |
|---|---|
| 1 | E2 Collection, E7 Release Engineering |
| 2 | E3 Progress, E6 Discovery, E8 Settings & Polish |
| 3 | E4 Stats, E5 Notifications |
| 4 | Release candidate: QA pass, store listing, closed-track rollout |

---

## EPIC 0 — Foundation & Setup ✅
Repo config + `.gitignore`, rename to Muviss, version catalog, `build-logic` convention plugins, TMDB key via generated `MuvissBuildConfig`, Koin, SQLDelight, Ktor client, Coil3, kotlinx.serialization, Navigation Compose, Spotless + Detekt + pre-commit + CI, all `:core:*` and `:models` modules, all five feature slices as compiling skeletons, agent docs.

## EPIC 1 — Search & Discovery ✅
Real end-to-end reference slice. `TmdbProvider`, debounced search + weekly trending, media detail (movie + TV with seasons/episodes). Repository over `MetadataProviderRegistry`; `SearchViewModel`/`DetailViewModel`; unit + ViewModel tests.

## EPIC 2 — Collection (Library) ✅
Wire `:core:database` into DI (new `databaseModule`, register in `AppModules.kt`); implement `feature/collection` for real, copying the search slice structure.
- `CollectionRepository` (domain interface + SQLDelight impl in data) over `CollectionEntry.sq`: add/remove (soft-delete), favorite toggle, observe library as `Flow`.
- Expose add-to-collection + favorite in `collection:api`; consume from search's DetailScreen (cross-feature via `:api` only).
- Collection screen: tabs/filters by derived `WatchStatus` (Not started / Watching / Watched / Finished) + Favorites; grid of `PosterImage` cards.
- Snapshot refresh: on app open, re-pull saved titles from TMDB to update `airedEpisodes` / `productionStatus`.
- Tests: repository (JVM in-memory driver), ViewModel.

## EPIC 3 — Progress (episode tracking) ✅
The core tracking loop: tick episodes seen.
- `ProgressRepository` over `EpisodeProgress.sq`: per-episode seen toggle, mark-season/mark-all, movie watched toggle.
- Derive `WatchStatus` via existing `WatchStatusCalculator` (`:core:model`) — never stored.
- Detail screen: checkmarks per episode, season progress bars, "mark previous as seen".
- Progress tab = "Watch next": currently-Watching shows with next unseen episode, one-tap tick.
- Status transitions (Watched→Watching when new episode airs) surfaced after snapshot refresh.
- Tests: repository + status-derivation integration + ViewModel.

## EPIC 4 — Profile & Stats ✅
- Local profile (name, avatar from a preset set) persisted via DataStore or a new settings table.
- Stats computed from DB: titles by status, episodes seen, estimated hours (episode runtime from TMDB details — extend `TmdbProvider` DTOs), genre breakdown, streaks.
- Simple charts (Compose canvas; no new heavy deps — verify wasm-js publish before adding any lib).

## EPIC 5 — New-episode notifications (Android) ✅
- Background refresh via WorkManager (androidApp; scheduling behind an expect/actual or Android-only Koin binding).
- Compare stored `airedEpisodes` vs fresh TMDB data → local notification "S02E05 of X is out".
- Notification permission flow (API 33+), settings toggle global and per-show.

## EPIC 6 — Discovery & Where-to-Watch ✅
- Extend `TmdbProvider`: `/discover/movie|tv` (genre/popularity), `/genre/*/list`, `/watch/providers` (region-aware; JustWatch attribution required by TMDB terms), pagination in search results.
- Discover browse UI in the search tab (genre rows, "popular now"); "where to watch" section in Detail.
- Region/language plumbed from settings (E8).

## EPIC 7 — Release Engineering ✅ (manual pre-tag steps in RELEASING.md §7)
Everything between "builds" and "on the Play Store".
- Release signing config (keystore via env/`local.properties`, never committed) + Play App Signing.
- R8: enable minify, keep rules (Ktor / kotlinx-serialization / SQLDelight / Koin pitfalls).
- Versioning automation (versionCode from git tag / CI run number).
- Sentry KMP integration (`:core:common` init seam; DSN via build config).
- Explicit `INTERNET` permission, review `allowBackup`, Material3 theme in manifest, current `targetSdk`.
- CI release job: tag → signed AAB → artifact (Gradle Play Publisher or manual first release).
- LICENSE file + OSS attribution screen (license-report plugin); privacy policy; TMDB attribution ("This product uses the TMDB API but is not endorsed or certified by TMDB"); Play data-safety answers.
- Store listing assets: screenshots, feature graphic, description.

## EPIC 8 — Settings & UX polish ✅
- Settings for real: theme (light/dark/system), TMDB language/region (feeds E6), about + licenses, data export (JSON dump of DB).
- Bottom-nav icons (bundle vector assets manually; `material-icons` unpublished for this Compose version).
- Empty states, error states, pull-to-refresh, loading skeletons across screens.
- Baseline profile + startup performance pass (nice-to-have).

## EPIC 9 — Sync & Accounts (post-v1)
`SyncEngine` over the existing `isDirty` / `updatedAt` / soft-delete change-log; backend TBD (Supabase vs Firebase); optional auth; web DB driver. No v1 work beyond keeping dirty-tracking intact.

## Cross-cutting / backlog
- Additional `MetadataProvider`s (TVmaze/Trakt) + cross-source reconciliation via IMDb id.
- File-backed desktop DB driver with schema versioning.
- iOS / Desktop / Web productionization (post-v1).
