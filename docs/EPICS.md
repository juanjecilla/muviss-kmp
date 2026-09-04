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
| 9 | E19 Triage, E20 Detail rework, E21 Most rewatched, E22 Home-screen widgets |

## EPIC 10 — Platform data parity ✅ — wave 5
The DB works everywhere or the "all platforms" story is fiction.
- Fix #10: `:core:model` JS-target serialization compile break.
- SQLDelight web worker driver for JS + Wasm (`DatabaseFactory.{js,wasmJs}.kt` currently throw); verify wasm support in current SQLDelight, document limits.
- File-backed JVM driver (desktop) replacing in-memory, with real `.sqm` migrations + `verifyMigration` wired now that the schema is stable (retro-baseline current schema as migration 1).
- `DataExporter` real impls for iOS (share sheet), JVM (file save dialog), web (download).

## EPIC 11 — iOS productionization ✅ — wave 6
- Xcode project polish: signing, bundle id, version from git (match Android scheme), Sentry iOS init.
- Local notifications: `UNUserNotificationCenter` + `BGAppRefreshTask` background refresh reusing `RefreshAndFindNewEpisodesUseCase` diff (already common code).
- TestFlight pipeline (CI or documented manual), App Store listing docs (reuse `docs/store/`).

## EPIC 12 — Desktop productionization ✅ — wave 6, depends on E10
- Compose Desktop packaging: DMG / MSI / DEB via `compose.desktop` `nativeDistributions`; app icon.
- File-backed DB (from E10), window size/position persistence, desktop-appropriate navigation polish.
- CI job attaching installers to tagged releases.

## EPIC 13 — Web productionization ✅ — wave 7, depends on E10
- Wasm build deployed (GitHub Pages or similar) from CI.
- PWA manifest + icons; document offline limits.
- Web-specific UX pass (responsive grid, keyboard focus).

## EPIC 14 — Calendar & Upcoming ✅ — wave 5, all platforms
TV Time parity: upcoming schedule. Air dates already mapped (`airDateEpochDay`).
- "Upcoming" view (new tab or Progress section): next air dates for saved shows, grouped by day; agenda list first, month grid optional.
- No new endpoints. It reads the same episode catalog watch-next does — in memory until EPIC 22 stored it (ADR 0015), so despite what this line used to claim, it was a network read per show until then; it is genuinely offline now.

## EPIC 15 — Ratings & notes ✅ — wave 6, all platforms
Personal (local, no social): 1–10 rating + free-text note per title.
- Schema: columns or small table; surfaced in Detail + Collection sort/filter; ratings feed Profile stats (avg rating, top genre by rating).

## EPIC 16 — Recommendations ✅ — wave 7, all platforms
- TMDB `/recommendations` + `/similar` behind `MetadataProvider` seam.
- "More like this" row in Detail; "For you" section in Discover seeded from library favorites/top-rated.

## EPIC 17 — Custom lists ✅ — wave 7, all platforms
TV Time parity: user lists ("Marathon 2026", "With Ana").
- Schema: `list` + `listEntry` join tables (dirty-tracked for future sync); CRUD UI in Library; add-to-list from Detail.

## EPIC 18 — Import ✅ — wave 8
- Import from Trakt export / TV Time takeout / generic CSV: map external ids (IMDb/TMDB) → `MediaId`, create collection entries + progress ticks.
- Groundwork shared with additional `MetadataProvider`s (TVmaze/Trakt) from the backlog.

## EPIC 9 — Sync & Accounts ✅ — wave 8 (moved from post-v1 backlog)
`SyncEngine` (`:core:sync`) over the existing `isDirty` / `updatedAt` / soft-delete change-log, behind a backend-agnostic `SyncBackend` seam; Supabase implemented first via plain Ktor (Firebase swappable later), optional email-OTP auth, last-write-wins conflict resolution, tombstone propagation. See ADR 0009 and `docs/SYNC.md`. Issue #8.

## EPIC 19 — Triage ✅ — wave 9
Swipe-deck triage (`:feature:triage`) for filling a library fast and then keeping up with what's new. Four verdicts — Skip / Later / Watching / Caught up — recorded in a `triageDecision` log (ADR 0010) so the deck never asks twice, even after a title is later removed from the collection. Caught up writes real aired-episode ticks and lets `WatchStatusCalculator` derive Watched/Finished (ADR 0005); nothing stores a status. Entered from Discover and Settings, not a sixth bottom-bar tab. Two drag schemes behind a `FeatureFlags` seam, keyboard parity on desktop/web, decisions synced like any other user-owned table. See ADR 0010.

## EPIC 20 — Detail rework & rewatch history ✅ — wave 9

Collapsible seasons with aired-only bulk marks, a five-star rating (display-only; storage stays 1-10), the note behind a one-line affordance, a per-episode detail screen, and `episodePlay` — real rewatch history (ADR 0011, schema v7). Also fixed the Library's never-ending pull-to-refresh spinner (no `HttpTimeout` + a fully sequential refresh) and the crash where marking a currently-airing season seen pushed `seenEpisodes` past `airedEpisodes`.

## EPIC 21 — Most rewatched ✅ — wave 9

The ranking ADR 0011 kept the door open for. A "Most rewatched" card on Profile opening a full screen: shows and movies ranked separately by *rewatches* — viewings beyond the first, per element, so a first watch-through scores zero and a long show cannot outrank a film by being long — under an All time / This year toggle, over a fixed trailing-twelve-month trend chart. A rewatch is a play with an earlier play of the same element **at any date**, so a window never re-reads a December first viewing as March's first watch. No schema change: two queries over `episodePlay` alone, joined onto the library in Kotlin, which is also why a removed title leaves the ranking while its history stays on disk. See ADR 0012.

## EPIC 22 — Home-screen widgets ✅ — wave 9

"Watch next" on the home screen, on both platforms that have one: shows in progress, each with its next unseen aired episode and a one-tap tick with Undo. Android is Glance in `:app:androidApp` (a receiver runs in the app process, so it resolves the existing Koin graph like `NewEpisodesWorker` does); iOS is a SwiftUI WidgetKit extension under `app/iosApp/MuvissWidget`, since Compose cannot draw in a widget.

The feature turned on something the roadmap had not noticed: the episode catalog was in-memory and cold each launch, so "next unseen episode" could not be computed offline at all. It is stored now — new `episode` table, schema v9, provider cache with no sync columns and no backfill (**ADR 0015**) — which also makes ADR 0002's offline-first promise true for episodes and fixes E14's claim above. The watch-next join moved out of `ProgressViewModel` into `WatchNextUseCase` behind `ProgressApi.observeWatchNext()`, so the screen and both widgets share one answer.

On iOS the database moved into a `group.com.codingpit.muviss` App Group with a one-time move-on-open (**ADR 0016**): a widget extension is a separate process, and its interactive buttons write. Signing it needs a real Apple team and a registered App Group — manual steps in `docs/RELEASING.md` §11; the Kotlin side falls back to the old Documents path without them.

Colour is one source: `MuvissPalette` (`:core:designsystem`) holds the literals, the M3 schemes are built from it, Glance builds `ColorProviders` from those, and the SwiftUI widget carries the same hexes. Refreshes are pushed from the write itself through a `WidgetRefresher` seam in `:core:common`, plus a reload at local midnight so the "aired by today" boundary moves.


## EPIC 23 — Desktop parity ✅ — wave 9 — issue #38

Desktop was not rotting the way iOS was before #37: CI compiled it on every PR,
every `expect` had a real JVM `actual`, and no feature slice was excluded from
`jvm()`. Four things were wrong anyway, two of them visible to a user.

**Sign-in dead-ended in silence.** `ProfileScreen` is `commonMain` and ungated,
so desktop offered the button, opened the browser, and `muviss://auth-callback`
had nowhere to land — no scheme in `nativeDistributions`, no `oauthCode` passed
to `MuvissApp()`. `CompleteOAuthOnRedirect` never fired and so `SignInFeedback`
never reported: precisely the failure ADR 0014 was written to prevent, on a
platform it did not cover. Fixed with a loopback HTTP server (RFC 8252) behind a
Koin-bound `OAuthRedirectTarget` — **ADR 0017**, which also records why the
custom scheme is not available to desktop and why a missing allow-list entry
fails only *after* the user authorizes.

**The Settings notifications switch did nothing**, and its "coming soon" copy
also understated Android and iOS, where it has worked since EPIC 5 and EPIC 11.
Desktop now has tray notifications and a 12h in-app refresh over the same
`RefreshAndFindNewEpisodesUseCase` both other platforms use, with per-platform
copy that admits desktop only checks while the app is open (#42).

**CI compiled desktop and nothing more** — never linked, packaged or launched,
though a wrong jlink module is a runtime failure rather than a build one. It now
packages and smoke-launches the *packaged* binary on every PR, builds the DMG on
the macOS runner, and `release.yml` gained `workflow_dispatch` plus the
Windows/MSI leg it had been avoiding. `desktop-release` had never run at all:
it triggers on a `v*` tag and this repo has none.

`:app:desktopApp` got its first tests, and the hand-maintained `jvmTest`
allowlist became the task name — 34 modules instead of 14, all passing. That is
half of **#25**; its other half (JS/Wasm tests, and `:app:androidApp`'s own unit
test, which is not a `jvmTest` task) is still open.

Filed rather than fixed: unsigned installers (#39), placeholder icon art (#40),
no menu bar or shortcuts (#41), no refresh while closed (#42), the jlink list
being unverifiable (#43), the MSI never having been installed (#44), no GitHub
Release object (#45), and web as the last target without sign-in (#46).

## Cross-cutting / backlog

**Everything here has an issue.** The tracker is the source of truth for undone
work; this list is the map. Nothing should appear below without a number beside
it — see `AGENTS.md` for why.

- **Durable web persistence** — #28. The SQL.js worker keeps the database in memory with no OPFS/IndexedDB backing (ADR 0008), so a page reload loses everything, including the triage decisions whose whole promise is not asking twice. Needs an ADR 0008 amendment.
- **Analytics + remote feature flags** — #29. `AnalyticsTracker` and `FeatureFlags` are seams in `:core:common` with no vendor behind them. Choosing one means a `wasm-js`-capable SDK, a consent flow, and a rewrite of `docs/PRIVACY.md`, which reverses a stated product principle — ADR-worthy on its own. Until then E19's two control schemes ship as a user preference and **cannot be compared empirically**.
- **`:app:macrobenchmark` + baseline profile** — #30. Frame timing for the triage drag, deck cold start, commit latency. Blocked on an emulator in CI, which is why the perf tests we do have use deterministic operation-count budgets instead.
- **Additional `MetadataProvider`s (TVmaze/Trakt)** — #31. Cross-source reconciliation via IMDb id. ADR 0001 and ADR 0006 built the seams; TMDB is still the only implementation, so neither has been exercised by a second source.

### Raised by EPIC 22, deliberately not done

- **The iOS widget extension has never been built** — #23. The Xcode target is checked in and its graph parses, but building it needs Xcode, a real `TEAM_ID` and a registered App Group. The App Group database relocation (ADR 0016) is unverified with it.
- **Android widget polish** — #27. The widget picker preview is still the KMP template's green robot (the app icon was never replaced), a tall widget with one show leaves empty space, and `WidgetMidnightRefresh` has never been observed firing.

### Raised by getting iOS running on the simulator (2026-09-02)

The app had not been built since EPIC 11 in July, because nothing in CI ever
compiled it — `ci.yml` was Linux-only and `allMetadataJar` compiles `iosMain`
to a metadata klib without invoking Kotlin/Native or Xcode. Two hard link
failures had accumulated unseen (Sentry and sqlite3 symbols left unresolved by
the static `Shared` framework); both are fixed, and CI now has a `macos-latest`
job so it cannot happen silently again.

- **iOS sign-in should use `ASWebAuthenticationSession`** — #33. OAuth works
  now, but through `UIApplication.openURL` — an app switch to Safari with an
  "open in app?" prompt. A UX improvement, not a correctness fix; `Pkce.ios.kt`
  claimed it was required, which was wrong.
- **The iOS app icon is still the KMP template's blue** — #34, alongside #27's
  Android equivalent. Its App-Store-blocking alpha channel is fixed.
- **iOS is Apple-Silicon only and nowhere says so** — #35. No `iosX64` target,
  `ARCHS = arm64` everywhere.
- **`MuvissWidget` has no shared scheme; `productReference` is misnamed** —
  #36. Both KMP-template leftovers in the hand-maintained `project.pbxproj`.

The widget target now *links* (see #23, which stays open: nothing about the
widget has been run).

### Found while landing EPIC 22, not caused by it

- **`OAuthRedirectCompletionTest` fails on js/browser** — #24. Reproduced on clean `main` at `e062b2b`, before the rebase.
- **CI compiles JS/Wasm but never tests them**, and its explicit `jvmTest` module list has drifted behind the modules that exist — #25. That is why #24 went unnoticed.
- **Two ADRs are both numbered 0012** — #26. Parallel branches each took the next free number; the schema version collided the same way and `verifyMigrations` cannot see it.
