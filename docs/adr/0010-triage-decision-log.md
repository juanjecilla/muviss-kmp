# Triage decisions are a log beside the collection, not a flag on it

Triage (see `CONTEXT.md`) lets the user rule on a stream of candidate MediaItems in one pass. Its whole value rests on never asking twice, so every verdict has to be remembered — including the verdicts that save nothing.

## Decision

A `triageDecision` table, keyed by `mediaId`, holding the verdict, when it was decided, and a minimal title/poster snapshot. It carries the ADR 0002 change-log columns and syncs like any other user-owned table.

It has **no foreign key to `collectionEntry`**, following `listEntry` (EPIC 17). Three of the four verdicts do write a collection row, but `Skip` deliberately does not — and a Skip is precisely the verdict that has to be remembered, because nothing else records it. A boolean on `collectionEntry` could only describe titles the user had already saved, which is the opposite of what triage is for.

The decision is a **log of intent, not a status**. `CollectionEntry` and `WatchProgress` remain the only truth about what is saved and how far it has been watched; a `TriageDecision` says only "the user ruled on this, on this date". The two can legitimately diverge — a title triaged `CaughtUp` and later deleted from the collection keeps its decision — and that divergence is the feature, not a bug: it is what stops a tidied-up library from resurfacing card by card.

`CaughtUp` respects ADR 0005 rather than working around it. It does not write a `WatchStatus`; it writes real ticks — `markAllAiredSeen` for TV, `setMovieWatched` for a movie — and lets `WatchStatusCalculator` derive `Watched` or `Finished` from those. One swipe on a long-running show therefore writes many rows. That cost is accepted: the alternative, a "seen through here" watermark, would be a second representation of progress and exactly the contradictory-state problem ADR 0005 exists to prevent.

> Amended: this originally said `markPreviousSeen`, which is not what shipped. `markPreviousSeen` walks a contiguous prefix and so would also tick anything *unaired* positioned before the target — an undated season-0 special is the usual case — and `WatchProgress`'s `require(seenEpisodes <= airedEpisodes)` then throws when the library reads its own status back.

## Considered options

- **A skip list only.** The other three verdicts are already observable as collection membership, so one table of skipped ids would dedupe the deck for a fraction of the code. Rejected because deleting a title from the collection would make it deck-eligible again, and because there would be no way to undo a mis-swipe once the snackbar had gone.
- **A flag on `collectionEntry`.** Cheapest schema change, matches `favorite`/`notificationsMuted`. Rejected: it cannot represent a Skip, which never produces a collection row.
- **System-owned `MediaList`s.** Zero schema change, sync already covers `mediaList`/`listEntry`. Rejected: it puts rows the user did not create into the user's own lists UI, and `mediaList` has no system-vs-user distinction to hide them with.

## Consequences

- A second table can disagree with collection state. Mitigated by naming — it is a decision log — and by never reading it to answer "what is saved" or "how far have I watched".
- Skipped titles are filtered out of Discover's "For you" (a suggestion surface) but **not** out of search results or the Popular carousels, which must keep returning the whole catalogue.
- `CaughtUp` on a large show produces a burst of `episodeProgress` rows, all dirty, all pushed on the next sync. Deliberate.
- Adding a synced table costs the six touchpoints ADR 0009 enumerates, including hand-run SQL in `docs/SYNC.md`.
