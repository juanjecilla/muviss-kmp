# Muviss — Epics

Roadmap. v1 (**Android, Play Store, local-only**) is feature-complete: EPICs 0–8 ✅. v2 targets **all platforms** (iOS, Desktop, Web) plus the next feature tier. Each epic maps to one GitHub issue and is scoped so a single agent can implement it end-to-end. Benchmark: [TV Time](https://www.tvtime.com) feature set, minus social/community (Muviss is user-focused, no social — see `CLAUDE.md`; EPIC 41 carves one narrow, deliberate exception and ADR 0022 states its limits).

## TV Time parity map

| TV Time feature | Muviss v1? | Epic |
|---|---|---|
| Track everything you watch (episode check-off) | Yes | E2, E3 |
| Watchlist / "watch next" | Yes | E2, E3 |
| Discover / trending / genres | Search + trending done; discover browse pending | E6 |
| New-episode notifications | Yes (local notifications, Android) | E5 |
| Viewing stats (episodes, hours, genres) | Yes (computed locally) | E4 |
| Where to watch (streaming availability) | Yes — TMDB watch/providers | E6 |
| Community / social / comments | **No — excluded by product principle**; the one exception is co-watch (pairwise, opt-in, no feeds or profiles — ADR 0022) | E41 |
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

## EPIC 24 — Durable web persistence ✅ — issue #28

Web was the one platform that forgot everything on a page reload. SQL.js keeps
the database in the worker's memory and EPIC 13 shipped that as a deliberate cut
(ADR 0008), which made triage's whole promise — never asking about the same
title twice — false on the web, and blocked web sign-in (#46) besides: a session
kept in a database that dies on reload signs the user straight back out.

**The fix was not a configuration.** `@cashapp/sqldelight-sqljs-worker` is sixty
lines with `new SQL.Database()` hard-coded and no `export()`, no VFS and no
hook, so the only way through was to ship our own worker — which is legitimate,
because `WebWorkerDriver` speaks nothing but a `postMessage` protocol. Ours
restores an IndexedDB snapshot on open and re-exports the database ~500ms after
the last committed write, with a `flush` on `pagehide`. It is a **local npm
package** rather than a loose file so that webpack still bundles its own
`sql.js` import — the trap ADR 0008 documents, which presents as a worker that
loads with a 200 and then hangs silently.

Two things fell out of it. **Web gained an upgrade path it never had**:
`SchemaEnsuringDriver` used to run `Schema.awaitCreate` unconditionally, correct
only while the database was always empty, and now reads `PRAGMA user_version`
and creates, migrates or leaves alone — so the `.sqm` chain covers web instead
of covering it vacuously. And **multi-tab became a real question**: two tabs are
two workers over one IndexedDB slot, so a Web Lock elects a single writer rather
than letting a stale tab overwrite a live one's work. Chosen against OPFS, whose
`opfs-sahpool` VFS would simply refuse the second tab; ADR 0008's amendment
records the trade and the trigger to revisit.

That amendment also **corrects** the previous one: `{ type: "module" }` never
reaches the browser (webpack emits `{type: void 0}` and a classic chunk), so the
worker is classic and its `importScripts` guard is what installs the handler.

# v3 — Production

Written 2026-09-19 after a repo, docs and CI audit. Roadmap code-complete (EPICs 0-24) is not the same as releasable: nothing has ever been signed, tagged, uploaded or listed, and CI has been refused for billing reasons since 2026-09-05 (last green `main` was `27715d6` on 2026-09-04, so everything after `a54f656` is unverified by CI).

**Decisions (2026-09-19).** All five targets ship (Play, App Store, signed DMG/MSI/DEB, web). The repo stays private until ready, then goes public as a release step. Sync ships as a **paid** feature sold through **RevenueCat**; that makes accounts real and drags in account deletion, Sign in with Apple, a privacy rewrite and a Supabase deploy pipeline (EPIC 32). Only a Play Console account exists today.

**Schema numbers, as they actually landed** (`AGENTS.md`). The claims made here on 2026-09-19 were overtaken by the order things merged, so this records the outcome rather than the plan: **`9.sqm` (schema v10) is EPIC 39's** — it merged first (#95) and carries `syncCursor`, `syncState` and EPIC 40's `appSettings.syncAutomatically` switch column; `10.sqm` (schema v11) is EPIC 26's (#103); `11.sqm` (schema v12) is EPIC 28's. **EPIC 40 needs no migration of its own** — its column came with EPIC 39's. ADR **0019** is EPIC 32's, **0020** is EPIC 39's, **0021** is EPIC 40's, **0022** is EPIC 41's. EPIC 41 takes **`11.sqm` (schema v12)** — one column on `collectionEntry` plus the co-watch tables. **It collides with EPIC 28 (#70), which claimed `11.sqm` first and has not merged**; the chain must stay contiguous, so whichever of the two merges second renumbers to `12.sqm` and regenerates its fixture. This is the collision the paragraph below describes, caught by rebasing rather than by a tool. **Outcome: EPIC 41 merged first (#134) and kept `11.sqm` (schema v12); EPIC 42 then took `12.sqm` (schema v13), so EPIC 28 must renumber to `13.sqm`.** ADR **0023** is EPIC 42's.

`verifyMigrations` cannot see a collision between branches: each one is self-consistent and green on its own, and the clash only appears at merge. So the number a branch takes is whatever is free when it merges, not when it was written — check this line, not a plan, before adding a `.sqm`.

| Wave | Epic | Issue |
|---|---|---|
| 0 | Manual actions only the owner can take (billing, accounts, certs, artwork) | #66 |
| 10 | EPIC 25 CI that gates again | #67 |
| 10 | EPIC 26 Crash reporting that reports | #68 |
| 10 | EPIC 27 Network hardening — **merged 2026-09-20** (#96); follow-ups #89-#94 | #69 |
| 10 | EPIC 28 Data-layer performance | #70 |
| 10 | EPIC 38 Repo hygiene and shared test infrastructure | #71 |
| 11 | EPIC 29 A backup you can restore | #72 |
| 11 | EPIC 30 Error, empty, offline and first-run UX; Android host fixes | #73 |
| 11 | EPIC 31 Localization (Spanish + app-language selector) | #74 |
| 11 | EPIC 31b Accessibility | #164 |
| 11 | EPIC 32 Sync goes live, paid via RevenueCat (start in wave 10; needs ADR 0019) | #75 |
| 11 | EPIC 39 Sync correctness: server sequence cursor, paged pull, null clearing, races. **Prerequisite for EPIC 40** — client and schema **merged 2026-09-20** (#95); #85 stays open for the live-project checks (#88, #100); follow-ups #97-#102 | #85 |
| 11 | EPIC 40 Opt-in automatic sync: build flag `SYNC_BACKGROUND_ENABLED`, per-device switch (default off), background triggers per platform | #86 |
| 11 | EPIC 41 Co-watch: a Shortlist of what two Companions can watch together (**blocked on EPIC 32**; needs ADR 0022, `11.sqm` — collides with EPIC 28, see the claims note) | #118 |
| 11 | EPIC 42 Snooze: postponing a triage decision (ADR 0023, `12.sqm`) | #135 |
| 12 | EPIC 33 Brand and store assets, hosted privacy policy | #76 |
| 12 | EPIC 34 Android to Google Play | #77 |
| 13 | EPIC 35 iOS to TestFlight and the App Store | #78 |
| 13 | EPIC 36 Desktop signed, notarised installers on a GitHub Release | #79 |
| 13 | EPIC 37 Web to production | #80 |
| 14 | Public flip and v1.0.0 launch | #81 |

EPIC 38 sits in wave 10 despite its number: it is parallel, low risk and speeds up the rest.

Filed as follow-ups rather than epics: desktop auto-update (#82, from EPIC 36) and web crash reporting (#83, from EPIC 26) — **fixed 2026-09-29**, web now loads Sentry's browser SDK from Sentry's CDN instead of waiting on `sentry-kotlin-multiplatform`'s web target. From the sync audit: duplicate plays when two devices tick the same episode (#87, filed twice as #97) — **fixed 2026-09-28** (#142, ADR 0013's amendment) — and confirming null clearing and the hosted `max_rows` against the live project (#88, owner-only).

**EPIC 39 and 40 (added 2026-09-20).** The owner reported that data after syncing is "not well done". An audit confirmed it: pull is unpaginated against a 1000-row server cap and its cursor is a max of client clocks, so a large library or an offline edit pushed late is silently never pulled; nulls are dropped from the push, so clearing a rating or note does not reach the server; `clearDirty` can mark an edit made during a push as clean; the snapshot refresh re-dirties and un-deletes rows and overwrites other devices' edits; and a pull can leave more seen than aired episodes, which throws. EPIC 39 fixes those with a server-assigned sequence cursor, paged and chunked transfer, transactional apply and a post-pull reconciliation. EPIC 40 then adds automatic sync that is off by default: a build flag, a per-device user switch, and platform triggers (WorkManager, BGAppRefreshTask, a desktop timer, web visibility events, plus a debounced push after local writes), gated inside `SyncEngine` rather than only in the UI. EPIC 32 keeps the account-switch policy and account deletion; 39 builds the mechanism. Details are in the two issues.

## What the audit found, by epic

- **EPIC 25 (#67).** `:app:androidApp` and `:app:desktopApp` tests run nowhere (a recurrence of #25 for the two non-KMP modules). Android Lint, `licensee`, R8 and every iOS test never run in CI. `kotlin-js-store/` is gitignored so web builds are unpinned. Release signing silently falls back to debug.
- **EPIC 26 (#68).** Android initialises Sentry inside a composable, so background-worker, Koin-startup and pre-first-frame crashes are invisible. No R8 mapping upload, so traces are obfuscated. No opt-out.
- **EPIC 27 (#69).** TMDB 429 and 401 responses parse as empty result pages ("No titles found"). No retry or HTTP cache. The API key is in URLs that leak into user-visible errors and Sentry. Serial season and catalog fetches.
- **EPIC 28 (#70).** The Library opens one Flow per entry and rebuilds all of them on any write: about 10,000 queries on a 100-title refresh. Missing indexes. Detail composes every episode eagerly.
- **EPIC 29 (#72).** Export omits ratings, notes and every custom list, has no version, and cannot be imported (the detector sends it to the Trakt parser). No "delete all data". iOS import is a dead button.
- **EPIC 30 (#73).** Six screens have no error retry. Refresh failures are silent. No offline detail. No onboarding. Notification permission asked cold. Light-only window theme flashes white in dark mode. `WidgetMidnightRefreshWorker` has no R8 keep rule, so the midnight refresh breaks in release builds.
- **EPIC 31 (#74).** Zero localised strings (~112 `Text("` + 84 named-arg literals in commonMain UI, plus English copy in `MetadataError`, `SyncCopy`, widgets and notifications) against a Spanish store listing; no in-app UI-language selector.
- **EPIC 31b (#164).** Accessibility, split from #74: the genre donut announces nothing, colour-only state, 44dp targets.
- **EPIC 32 (#75).** Sync is built but unobtainable (every paywall is `HandRolledPaywall`). Turning it on needs account deletion, Sign in with Apple, a privacy rewrite, safe sign-out (the sync cursor is never reset) and a Supabase deploy pipeline.
- **EPIC 33 (#76).** The Android launcher icon is the Android Studio template. No screenshots, feature graphic or fastlane. No hosted privacy-policy URL, which both stores require.
- **EPIC 34 (#77).** `material3` alpha and `lifecycle` beta ship in production UI. No baseline profile. The release path has never run.

## EPIC 41 — Co-watch (added 2026-09-21)

**Blocked on EPIC 32 (#75).** Not a preference: until sync ships and someone can pay, there are no two entitled users to co-watch. EPIC 39's client and schema (#95) are a hard dependency and have landed — the design assumes the `server_seq` cursor, paged pull and `syncState.ownerAccountId`.

Two accounts link by mutual consent and each publishes a **Watch Pool** — `NotStarted` entries by default, or a nominated `MediaList` — addressed to the other. Each device computes a **Shortlist** locally: a pure ranking function over data already in `collectionEntry` (both pinned → in both pools and neither started → in both pools → shorter runtime first). No scoring model; this app has no analytics to tune one, and adding them would itself reverse a stated principle (#29).

The whole design falls out of four invariants, stated in **ADR 0022**: a Companion's data is **rendered, never applied** (nothing another user publishes writes your `WatchProgress`); **no co-owned state** (pins are per-person, so last-write-wins is never asked a question it cannot answer); **purpose-limited** (only the Pool crosses the wire, so unlinking means something); and **flat RLS policies only** (`auth.uid()` comparisons, no subquery, no `security definer` RPC — RLS is the only boundary and the anon key ships in the binary).

Consequences worth knowing before touching it. Pool rows are **addressed** (`using (auth.uid() = user_id or auth.uid() = recipient_id) with check (auth.uid() = user_id)`) rather than shared, so N companions means N copies — chosen over a link-table subquery whose failure mode is a silent cross-user read. The invite code **carries the inviter's `user_id`** plus a nonce, because RLS cannot express "readable if you know the secret" without an RPC this project has never had. Companion data gets its own **`CompanionBackend`** seam beside `SyncBackend`; `SyncChangeSet` keeps meaning *the user's own change-log* and `SyncChangeSetShapeTest` should keep failing if anyone adds to it. A new `collectionEntry.revisitWillingness` column (**Revisit Willingness**, stored intent — deliberately not called a rewatch flag, since `Rewatch` is derived and never stored) is what makes already-seen titles reachable at all: a `WATCHED`/`FINISHED` title can never re-enter `WatchNextUseCase`, which filters `WATCHING`.

**The Shortlist is not WatchNext.** `CLAUDE.md`'s "one implementation" rule still holds for *what do I watch next* — that question is about `WATCHING`. The Shortlist asks *what should two people start*, over `NOT_STARTED` and `WATCHED`, the two sets WatchNext excludes. Do not fold them together.

Linking and unlinking live in Profile beside the sync row; the Shortlist is a section in Progress. No sixth bottom-bar tab, following Triage. Android, iOS and Desktop only — **web has no OAuth sign-in at all** (#46), an inherited gap rather than a new one. Both sides must be entitled; `EntitlementGate` inside `SyncEngine` stays the only check.

Explicitly out of scope, each with an issue: web (#119, blocked on #46), group (N > 2) UI (#120), any push channel to freshen a Companion's Pool (#121 — only their device can publish, so staleness is labelled rather than solved), watch-provider signals in ranking (#122 — `WatchProviders` is fetched live and never persisted), and cross-ticking "we watched this together" (#123, excluded by the first invariant).

**`docs/PRIVACY.md`, `docs/store/DATA_SAFETY.md` and `docs/store/LISTING.md` all become false** and are deliberately *not* amended yet — the feature does not exist, and amending them now would make them false in the other direction. They are amended in the same change that ships it, alongside the rewrite EPIC 32 already owes. The listing currently promises *"No social features — no feeds, no friends, no comments"* in words a store reviewer could quote back.

## EPIC 42 — Snooze: postponing a triage decision — wave 11 — issue #135

**ADR 0023**; `12.sqm` (schema v13). Started as "give a film a fourth action, *don't decide now*" and became something else once `CONTEXT.md` was read: a `TriageDecision` is a log of what the user **decided**, and exists so triage never asks twice. Declining to decide is not a fifth verdict — it is the absence of one.

So **Snooze** is its own concept: a `triageSnooze` table with a due date and a card snapshot, synced, soft-deleted. Reached from a button on the card (all six targets), with long press and `S` as accelerators. A settings PickerRow chooses how long it waits (1 week default / 1 month / 3 months / ask each time) and a second chooses where due titles re-enter the deck (mixed in by default, capped at two per batch — first or last on request). Undo, a "Snoozed" screen and a Detail banner are all there, because ADR 0010 already refused to leave that gap for skips.

Making it a fifth `TriageVerdict` would have been actively dangerous, not merely untidy: `fromStored` returns null for an unknown verdict, so the row vanishes from every read while `selectDecidedIds` still counts it — an older build or peer device would exclude the title from its deck forever with nothing able to show or undo it. See ADR 0023.

**Not done, deliberately**: a freely chosen date under "ask each time" (Material3's `DatePicker` is unverified on `js`/`wasmJs` and there is no `kotlinx-datetime`) — #137. Snoozes are absent from the data export — noted on EPIC 29 (#72). The deck header now carries three text actions and its title wraps on a phone — #138.

**Not verified by any test**: the live Supabase `triage_snooze` table and its RLS (same gap as #88/#129), and the gesture on a real touch device — `runComposeUiTest` renders the desktop path.

## EPIC 43 — A Supabase dev/staging project, separate from production — wave 0 — issue #151

There is exactly one linked Supabase project (`sodjedenvnvsuktbxevt`, "Muviss app"), and it is both production and the only place any live-project verification work can run: #88 (null-clearing, hosted `max_rows`), #100 (EPIC 39's server migration + pgTAP suite), #129 (co-watch RLS — including creating throwaway multi-account test data), and EPIC 42's own noted gap (`triage_snooze`'s RLS, same category). Every one of those either applies a real migration (`supabase db push`) or creates test rows against the project real users' data lives in.

Stand up a second free-tier project dedicated to dev/verification; decide and document the dev/prod switching workflow for the CLI (hard to get backwards by construction, not just convention); point `scripts/sync/verify-*.sh` at it by default with an explicit opt-in flag required to target prod; define a promotion workflow (verify on dev, then a deliberate, separate step applies to prod); evaluate Supabase's paid database-branching feature as an alternative. Once CI's billing outage (#66) is resolved, wire CI to run migration/RLS checks against the dev project automatically.

Top priority alongside #66 — every RLS/migration verification task in the backlog is unsafe to fully automate without this existing first. Done means a second project exists, is documented in `docs/SYNC.md` alongside the existing verification log, and at least one of #88/#100/#129 has been re-run against it end-to-end before anything is promoted to prod.

## Sequencing

Wave 0 first (nothing else runs without CI; EPIC 43 belongs here too — every live-project verification task depends on it existing before it can run safely). Wave 10 in parallel; start EPIC 32's ADR at the same time because it is the long pole. Wave 11 after the wave-10 network and migration work lands (EPIC 30 needs EPIC 27's `MetadataError`; EPIC 30 and EPIC 26 share a migration). Waves 12-13 need Wave 0 accounts and certificates. Wave 14 is the public flip.

Every epic's issue carries its own Testing section by layer and a platform-parity section (`muviss-test-and-platform-bar`): unit, migration, repository or integration, sync, UI (light and dark), performance by operation count, and an explicit note per target.

## The 2026-09-20 merge of the open-PR board

Nine PRs had been open since 2026-09-05 — the whole backlog of #55-#60 plus the
roadmap and the two epics — because **every check on every one of them was red
for the same reason**: Actions refuses to start a job while account billing is
failing (#66). None of those reds was a test failure.

All nine merged that day, reviewed by reading rather than by CI, with local
runs standing in where they could. What that did and did not buy:

- Merged: #56 (agent worktrees gitignored, #48), #57 (shared `MuvissWidget`
  scheme, #36), #84 (this roadmap), #58 (`verifyJlinkModules`, #43), #59
  (desktop menu bar, #41), #60 (the non-persisting tab says so, #53), #55
  (a tag produces a real GitHub Release, #45), #95 (EPIC 39) and #96 (EPIC 27).
- Three defects were found by review and fixed on their branches before merge:
  six unused imports in `MuvissApp.kt` that `spotlessCheck` would have failed
  on; a `PersistenceStatus` Koin binding declared in a module that never loads
  on Android, iOS or desktop, which would have crashed the first frame on three
  targets; and a `BoundedRefreshTest` fake that stopped compiling once EPIC 39
  split `refreshSnapshot` out of `upsertSnapshot`.
- The last of those is the shape of the risk this whole exercise carries: it
  was invisible in either PR alone and only appeared when the two were rebased
  together. `./gradlew jvmTest` on the final tree passed 2449 tests across 28
  modules, which is the `build` lane's test step and nothing more.
- **Still unverified on `main`:** Android Lint, R8, the packaged desktop app,
  every iOS target, both web targets at runtime, and anything against the live
  Supabase project. The first real signal arrives when #66 is done.

## Cross-cutting / backlog

**Everything here has an issue.** The tracker is the source of truth for undone
work; this list is the map. Nothing should appear below without a number beside
it — see `AGENTS.md` for why.

- **Landing site follow-ups** — the Astro landing in `website/` owns this repo's Pages at `muvissapp.com` and lists free features only (sync and co-watch are paid). Scoped out: **the Wasm web app** has no host now that the landing took the Pages root, moving to `app.muvissapp.com` #167; **screenshots** are an owner capture, shot list in `website/README.md` #168; **DNS + first deploy** blocked on the public flip #169; **store badges** flip live per channel in `website/src/config/links.ts` #170.
- **Full-build traps found verifying EPIC 41** — **#131**: Kotlin 2.4's Wasm incremental compilation corrupts its own cache and fails an *arbitrary* module with `NoSuchElementException: Key ic#… is missing in the map`, naming a stdlib symbol — it moved through four untouched modules across five runs. `-Pkotlin.incremental.wasm=false -Pkotlin.incremental.js=false` makes the build pass first try, which is the evidence. It compounds #49: between them, the full-build check `AGENTS.md` asks for is unreliable. **#132**: `spotlessKotlin` fails with no violation text inside a parallel `build`, and passes standalone.
- **Co-watch follow-ups** — raised by EPIC 41 (#118), all deliberately scoped out: **web** #119 (blocked on #46 — web has no OAuth sign-in, so no `auth.uid()` to publish a Watch Pool as); **groups larger than two** #120 (the domain models a set of participants, the UI ships pairwise; a 5-way intersection is usually empty and needs scoring rather than intersection); **a push channel** #121 (only a Companion's own device can publish their Pool — a structural ceiling, so staleness is labelled rather than solved; overlaps #86); **persisted watch providers** #122 (`WatchProviders` is fetched live and never stored, so "on a service you both have" needs a fan-out that does not exist — **done**: `titleWatchProviders` (cache, `13.sqm`) plus `companionPoolOut`/`In.flatrateProviderIds`, TTL/region-gated via `WatchProviderRefresher` so the Shortlist reads a local cache rather than fetching on open); **cross-ticking** #123 (forbidden by ADR 0022's first invariant, and by RLS).
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

- **`OAuthRedirectCompletionTest` fails on js/browser** — #24. **Fixed
  2026-09-04**: moved to `jvmTest`. The two tests assert Compose *runtime*
  semantics against hand-rolled subjects and reach no OAuth code on any
  platform — and web has no OAuth to reach (`Pkce.js.kt` throws), so running
  them there verified an arrangement for a feature that target does not have.
- **CI compiles JS/Wasm but never tests them**, and its explicit `jvmTest`
  module list has drifted behind the modules that exist — #25. **Reopened**:
  only the `jvmTest` half was done in EPIC 23. A `web-tests` job runs
  `jsBrowserTest wasmJsBrowserTest`, `continue-on-error: true` pending #49 and
  gated by the `changes` job (#65); `:app:androidApp`'s own unit test still
  does not run anywhere. Whether ~18 CI-minutes per PR is worth the
  platform-divergence signal — both web targets run the *same* 445 `commonTest`
  tests, all of which already run on the JVM — is EPIC 25's (#67) call.
- **The Kotlin compiler OOMs under parallel load** — #49. **Documented and
  deliberately not fixed**: the workaround is in `AGENTS.md` and the committed
  heap numbers are left alone, because the same values must hold on CI runners
  with ~14-16 GB and picking one blind trades an intermittent flake for a hard
  failure on the iOS job. Wants one deliberate CI experiment, not a guess. Six
  `:feature:*:domain` modules died with "Internal compiler error" on the first
  full JS/Wasm run and every one of them passed on a rerun; the same session's
  `./gradlew build allMetadataJar` then failed three iOS tasks with an explicit
  `java.lang.OutOfMemoryError: Java heap space` after 2h35m. Not wasm-specific
  and not about physical memory — the machine had 36 GB and the cap is
  `org.gradle.jvmargs=-Xmx4096M` (Gradle said so itself), which cannot simply be
  raised because the same value has to hold on CI runners with ~14-16 GB.
  `--max-workers=3` is **not** enough; raising the heap for the run is
  (`-Dorg.gradle.jvmargs=-Xmx12288M`), and `AGENTS.md` carries the command. It
  is why the `web-tests` job is non-blocking, and it must be resolved before
  that job can gate a PR.
- **CI never links the release iOS framework** — #50. The `ios` job runs
  `linkDebugFrameworkIosSimulatorArm64` and builds Xcode `-configuration Debug`;
  `linkRelease*` is reached only through `./gradlew build`, which no job runs.
  Found via #49: the release link dies in `DevirtualizationAnalysis`, a pass the
  debug link cannot reach. The shipping configuration is the unverified one.
- **Two ADRs are both numbered 0012** — #26. **Fixed 2026-09-04**:
  `0012-sync-is-gated-twice.md` became **ADR 0018** (0017 had been taken by
  EPIC 23 in the meantime, so the fix suggested in the issue was stale), and
  the rewatch ADR kept 0012 because it owned the number in roughly three times
  as many places. `AGENTS.md` gained the convention that closes the class:
  claim ADR and `.sqm` numbers when the branch opens, not when it merges.

### Raised by the "By genre" donut refinement (2026-09-08)

- **The profile screen's identity/sync/rewatch spacing still bypasses
  `MuvissSpacing`** — #63. The stats half was normalised onto the 4dp token
  grid while the donut was reworked; `IdentitySection`, `SyncSection`,
  `RewatchTrendChart` and `RewatchScreen` were left on raw `.dp` literals.
  `RewatchTrendChart` in particular is skipped because snapping its off-grid
  `6.dp`/`4.dp` forces a re-record of all five `rewatch-*.png` goldens.
- **The genre legend stretches the full width of a tablet** — #64. The wide
  layout gives the legend `weight(1f)` and two equal columns, so at ~1200dp a
  selected row's highlight pill spans ~500dp for a two-word label. Needs a width
  cap or more columns, plus a golden recorded at a genuinely tablet-sized frame —
  the committed wide goldens are 892dp, which is phone-landscape.

## EPIC 40 — Opt-in automatic sync — wave 11 — issue #86

Depends on EPIC 39 (#85, merged). **ADR 0021**; no schema number of its own (the `syncAutomatically` column and `syncState` came with EPIC 39's `9.sqm`). Off by default and gated three ways, all inside `SyncEngine.syncNow(trigger)`: a build flag (`SYNC_BACKGROUND_ENABLED`), a per-device switch on Profile, and the session and entitlement. Triggers: a debounced push after local writes and a foreground sync everywhere (`SyncCoordinator`), plus WorkManager (`SyncWorker`, hourly), a piggyback on iOS's background refresh, a 15 minute desktop timer while open, and web `visibilitychange`/`online` events (inert until web sign-in, #46). A weekly full pull bounds the sequence-gap risk. Failures are typed and finally visible on Profile; the switch's status line, "Resync everything" and "Session expired" are in. `AccountChanged` is surfaced and left to EPIC 32. See `docs/SYNC.md` "Automatic sync" and ADR 0021.

**Not verified by any test**, tracked as a checklist on #86: a real WorkManager run and the release-build `-keep` rule, iOS `BGAppRefreshTask` and its expiration handler, a packaged desktop app left open, two browser tabs, a live Supabase project (#88, #100).
