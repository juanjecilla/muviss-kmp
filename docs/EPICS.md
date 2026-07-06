# Muviss — Epics

Major bodies of work. Epic 0 and Epic 1 are delivered in the initial setup pass; the rest are scaffolded (compiling stubs) and filled in later.

## EPIC 0 — Foundation & Setup ✅ (this pass)
Repo config + `.gitignore`, rename to Muviss, version catalog, `build-logic` convention plugins, TMDB key via generated `MuvissBuildConfig`, Koin, SQLDelight, Ktor client, Coil3, kotlinx.serialization, Navigation Compose, Spotless + Detekt + pre-commit + CI, all `:core:*` and `:models` modules, all five feature slices as compiling skeletons, agent docs.

## EPIC 1 — Search & Discovery ✅ (this pass)
Real end-to-end reference slice. `TmdbProvider`, debounced search + weekly trending, media detail (movie + TV with seasons/episodes). Repository over `MetadataProviderRegistry`; `SearchViewModel`/`DetailViewModel`; unit + ViewModel tests.

## EPIC 2 — Collection
Save/remove a title (writing the offline snapshot), organize by derived status (Not started / Watching / Watched / Finished) and Favorites, list & filter the library. Wires `:core:database` into DI, adds the collection repository over SQLDelight.

## EPIC 3 — Progress
Episode-level and movie-level ticking, `WatchStatus` derivation surfaced in the UI, "next up", and a background refresh that re-pulls saved shows to flip Watched→Watching / Watched→Finished as episodes air or a show ends.

## EPIC 4 — Profile & Stats
Local identity (name/avatar), viewing statistics (titles, episodes, hours, genre breakdown), and the dormant "sign in to sync" entry point.

## EPIC 5 — Settings
Theme, TMDB region/language, data export, about.

## EPIC 6 — Sync (future)
Implement `SyncEngine` against a chosen free backend (Supabase or Firebase, TBD), replaying the local change-log (`isDirty`/`updatedAt`/soft-delete). Optional account/auth. Web persistence driver.

## Cross-cutting / backlog
- Bottom-nav icons (currently label-only; `material-icons` is frozen for this Compose version).
- Additional `MetadataProvider`s (TVmaze/Trakt) + cross-source reconciliation via IMDb id.
- File-backed desktop DB driver with schema versioning.
