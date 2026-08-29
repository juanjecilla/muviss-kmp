# The episode catalog is stored

`WatchProgress` records which episodes a person has ticked. It has never recorded which episodes *exist* — that came from TMDB on demand, into `EpisodeCatalogCache`, an in-memory `StateFlow` that was cold on every launch and never written anywhere. `EpisodeOrdering.nextUnseen` needs the full episode list to find the first aired one not ticked, so **"what do I watch next" could not be answered without a network round trip**.

That was invisible while a screen was the only caller: someone opening the Progress tab is, by definition, running the app, usually online, and a spinner for a second is unremarkable. EPIC 22's home-screen widgets are the opposite case in every respect — they redraw with no app running, often with no network, and have seconds of budget before the system gives up on them. A widget that could not name the next episode would be a widget with nothing to say.

It also means ADR 0002 — "offline-first, local source of truth" — was not true of episode data, and `CONTEXT.md` said *Snapshot* included the "episode list" when nothing ever stored one.

## Decision

**A new `episode` table stores each saved show's seasons and episodes, and `EpisodeCatalogCache` reads it before it reads the network.**

**The catalog is cache, not user data, and the distinction is load-bearing.** Every other user-owned table carries `isDirty` / `updatedAtEpochMs` / a soft delete because it records something a person did. This one records what TMDB says a show contains. It therefore has **no sync columns**, is **absent from `SyncChangeSet`**, and is **absent from the data export** — a refetch always wins over what is stored, so there is nothing to reconcile and nothing to back up.

**The migration backfills nothing.** `6.sqm` could seed `episodePlay` because the facts it needed were already on disk; here the facts are TMDB's and have never been stored. `7.sqm` creates an empty table (schema v8).

**A refetch replaces a title's catalog wholesale** rather than merging into it, so an episode the source has removed — a mis-numbered special, a restructured season — disappears locally too.

**A failed fetch changes nothing.** A stale catalog names a real episode; an absent one names nothing.

**The background refresh both platforms already run for new-episode notifications now refreshes catalogs too, ahead of the notification gate.** Turning notifications off is a statement about being interrupted, not a request for a stale widget.

## Considered options

- **Keep it in memory; let the widget fetch.** No schema change at all. Rejected on three counts: the home screen goes blank offline, it puts a per-show network fan-out on a background wake-up, and iOS gives a widget extension a tight time and network budget that a fan-out is a good way to exceed. It also leaves ADR 0002 untrue.
- **Store a denormalized "next up" projection** — just the computed next-unseen episode per title, on `collectionEntry`. Much smaller. Rejected because it caches a *derivation*: after a tick from the widget the projection is wrong, and recomputing it needs the catalog that was the thing not stored. It could advance exactly one step before going stale, and the Upcoming agenda (EPIC 14) still could not be served offline.
- **A `season` table beside the `episode` one**, normalized properly. Rejected: a season carries a number and a name and nothing else, and two tables turn one query per title into a join — while `seasonName` denormalized onto every row rebuilds `List<Season>` in a single ordered pass.
- **Backfill the catalog during the migration** by fetching every saved title. Rejected outright: a network fan-out on the upgrade path, where it can fail, hang, or run on a metered connection while a person stares at a launch screen.
- **Give the table `isDirty` "for symmetry"**, as `episodePlay` did. Rejected — `episodePlay` has it because plays *are* the user's data and only the merge rule was unsettled (see ADR 0011). Provider cache has no such future.
- **Widen *Snapshot* to include the episode list**, making `CONTEXT.md`'s existing sentence true rather than adding a term. Rejected: the snapshot is a row written once when a title is saved, the catalog is a table refreshed every twelve hours. One word covering two lifetimes stops "is the snapshot stale" from having a single answer.

## Consequences

- **Schema version 8**, via `7.sqm` and the regenerated `8.db` fixture. `verifyMigrations` covers the chain; `EpisodeCatalogMigrationTest` covers what it cannot — that a real library comes through, and that the table arrives *empty*.
- **A widget placed immediately after upgrading has nothing to name.** The table starts empty and fills read-through, so until the app runs once or the twelve-hour refresh fires, both widgets show an "open Muviss once" state rather than a wrong one. This is the accepted cost of not backfilling.
- **`ProgressApi` grew `observeWatchNext()` and `refreshWatchNextCatalogs()`**, and `WatchNextItem` moved into `:api`. The collection × catalog × ticks join moved out of `ProgressViewModel` into `WatchNextUseCase` so the screen and the two widgets share one answer; `feature/progress/domain` gained a dependency on `feature/collection/api`, which ADR 0004 permits.
- **`EpisodeCatalogCache` keeps its interface.** `ProgressViewModel` and `UpcomingViewModel` were not changed by the read-through, only by the seam move.
- **EPIC 14's Upcoming agenda becomes genuinely offline**, which `docs/EPICS.md` had already claimed it was.
- **On web the catalog is as durable as everything else, which is to say not** — SQL.js keeps the database in worker memory (ADR 0008), so a reload empties it along with the library. No worse than the rest, and it will be fixed with the rest.
- **Two things only a device could show, both now written down in `CLAUDE.md`.** Exposing this on `ProgressApi` closed a Koin construction cycle through collection's repository (fixed with a provider, guarded by `AppGraphTest`), and reading the list before `provideContent` froze the widget on the episode it had just ticked (fixed by collecting inside the composition). Two thousand unit tests and a green `./gradlew build` said nothing about either.
- **The export and the change set are now asserted, not merely intended.** `ExportShapeTest` and `SyncChangeSetShapeTest` fail if a future change adds a table to either without meaning to.
