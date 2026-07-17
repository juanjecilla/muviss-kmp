# Muviss — Epics

Roadmap. v1 (**Android, Play Store, local-only**) is feature-complete: EPICs 0–8 ✅. v2 targets **all platforms** (iOS, Desktop, Web) plus the next feature tier. Each epic maps to one GitHub issue and is scoped so a single agent can implement it end-to-end. Benchmark: [TV Time](https://www.tvtime.com) feature set, minus social/community (Muviss is user-focused, no social — see `CLAUDE.md`).

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

---

# v2 — All platforms + next feature tier

## Dependency graph (v2)

```
E10 Data parity ──► E13 Web release          E9 Sync ──► (cross-device value)
       │                                          ▲
       ├──► E12 Desktop release        E18 Import ┘ (shares id-mapping work)
E11 iOS release (independent)
E14 Calendar   E15 Ratings&Notes   E16 Recommendations   E17 Lists  (feature epics, all platforms)
```

| Wave | Epics |
|---|---|
| 5 | E10 Data parity, E14 Calendar |
| 6 | E11 iOS release, E12 Desktop release, E15 Ratings & notes |
| 7 | E13 Web release, E16 Recommendations, E17 Custom lists |
| 8 | E9 Sync & accounts, E18 Import |

## EPIC 10 — Platform data parity 🔴 v2 critical path — wave 5
The DB works everywhere or the "all platforms" story is fiction.
- Fix #10: `:core:model` JS-target serialization compile break.
- SQLDelight web worker driver for JS + Wasm (`DatabaseFactory.{js,wasmJs}.kt` currently throw); verify wasm support in current SQLDelight, document limits.
- File-backed JVM driver (desktop) replacing in-memory, with real `.sqm` migrations + `verifyMigration` wired now that the schema is stable (retro-baseline current schema as migration 1).
- `DataExporter` real impls for iOS (share sheet), JVM (file save dialog), web (download).

## EPIC 11 — iOS productionization — wave 6
- Xcode project polish: signing, bundle id, version from git (match Android scheme), Sentry iOS init.
- Local notifications: `UNUserNotificationCenter` + `BGAppRefreshTask` background refresh reusing `RefreshAndFindNewEpisodesUseCase` diff (already common code).
- TestFlight pipeline (CI or documented manual), App Store listing docs (reuse `docs/store/`).

## EPIC 12 — Desktop productionization — wave 6, depends on E10
- Compose Desktop packaging: DMG / MSI / DEB via `compose.desktop` `nativeDistributions`; app icon.
- File-backed DB (from E10), window size/position persistence, desktop-appropriate navigation polish.
- CI job attaching installers to tagged releases.

## EPIC 13 — Web productionization — wave 7, depends on E10
- Wasm build deployed (GitHub Pages or similar) from CI.
- PWA manifest + icons; document offline limits.
- Web-specific UX pass (responsive grid, keyboard focus).

## EPIC 14 — Calendar & Upcoming — wave 5, all platforms
TV Time parity: upcoming schedule. Air dates already mapped (`airDateEpochDay`).
- "Upcoming" view (new tab or Progress section): next air dates for saved shows, grouped by day; agenda list first, month grid optional.
- Uses snapshot refresh data only — no new endpoints.

## EPIC 15 — Ratings & notes — wave 6, all platforms
Personal (local, no social): 1–10 rating + free-text note per title.
- Schema: columns or small table; surfaced in Detail + Collection sort/filter; ratings feed Profile stats (avg rating, top genre by rating).

## EPIC 16 — Recommendations — wave 7, all platforms
- TMDB `/recommendations` + `/similar` behind `MetadataProvider` seam.
- "More like this" row in Detail; "For you" section in Discover seeded from library favorites/top-rated.

## EPIC 17 — Custom lists — wave 7, all platforms
TV Time parity: user lists ("Marathon 2026", "With Ana").
- Schema: `list` + `listEntry` join tables (dirty-tracked for future sync); CRUD UI in Library; add-to-list from Detail.

## EPIC 18 — Import — wave 8
- Import from Trakt export / TV Time takeout / generic CSV: map external ids (IMDb/TMDB) → `MediaId`, create collection entries + progress ticks.
- Groundwork shared with additional `MetadataProvider`s (TVmaze/Trakt) from the backlog.

## EPIC 9 — Sync & Accounts ✅ — wave 8 (moved from post-v1 backlog)
`SyncEngine` (`:core:sync`) over the existing `isDirty` / `updatedAt` / soft-delete change-log, behind a backend-agnostic `SyncBackend` seam; Supabase implemented first via plain Ktor (Firebase swappable later), optional email-OTP auth, last-write-wins conflict resolution, tombstone propagation. See ADR 0009 and `docs/SYNC.md`. Issue #8.

## Cross-cutting / backlog
- Additional `MetadataProvider`s (TVmaze/Trakt) + cross-source reconciliation via IMDb id.
- Home-screen widgets (Android Glance / iOS WidgetKit) — post-E14.
- Baseline profile + startup performance pass.
