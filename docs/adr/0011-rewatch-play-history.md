# Rewatching is a history of viewings, not a counter

> **Amended by ADR 0013 (2026-08-29).** This ADR kept `episodePlay` off the
> sync change-log, on the grounds that append-only rows need a different
> conflict rule than last-write-wins. That turned out to be a consequence of
> the table's per-device `AUTOINCREMENT` key rather than of append-only data:
> given an id derived from the viewing itself, last-write-wins applies
> unchanged. Rewatch history now syncs, the key is
> `episodeId@watchedAtEpochMs`, and this table's deletes are soft. See ADR
> 0013.

ADR 0005 made per-episode ticks the source of truth and said, in as many words, that progress carries no separate history log. That held for as long as the only question was *whether* something had been seen. It stopped holding the moment the answer needed to be "three times, most recently in March".

Until now, tapping an already-ticked episode un-ticked it. A mis-tap and a genuine second viewing were the same gesture, and the second one was unrepresentable.

## Decision

A new `episodePlay` table: one row per viewing, carrying `episodeId`, `mediaId`, and `watchedAtEpochMs`. Movies use `EpisodeId.forMovie`'s synthetic id, exactly as `episodeProgress` does, so rewatching a film is recorded identically.

**`episodeProgress.seen` stays a stored column.** It is not replaced by a view over the play table. The sync change-log keys off it, `clearForMedia` sets it, and un-ticking propagates as `seen = 0` (ADR 0009) — a derived column could do none of that. Instead both are written together, in one transaction, inside `SqlDelightProgressRepository` and nowhere else. That single write path is what stops "seen with no viewings" from existing. `playCount` is derived (`COUNT(*)`) and never stored.

Tapping an already-seen episode now asks which of the two things the user meant. "Watched again" appends a viewing. "Ticked by mistake" removes the **most recent** viewing only — an episode watched three times drops to two and stays seen, rather than losing the history behind the slip. Clearing an episode's history outright exists, but only as an explicit action on the episode detail screen, where it cannot be reached by a stray tap.

`6.sqm` creates the table and **backfills one row per already-seen tick**, carrying the original `updatedAtEpochMs` across. This is the reason the change ships as a migration rather than an empty table filled lazily: without it, every existing install would upgrade into a library claiming nothing had ever been watched. Backfilled rows are `isDirty = 0` — they describe history that already existed locally, not a change to push.

The profile's watch streak now reads play timestamps rather than `episodeProgress.updatedAtEpochMs`. Under the old reading, rewatching an old episode *moved* its day to today instead of adding one, silently erasing a past streak day.

## Considered options

- **A `playCount` column on `episodeProgress`.** Half the work: one `ALTER TABLE`, no new table, no new queries, and enough for a most-watched ranking. Rejected because it answers "how many times" and nothing else — "how often did I rewatch this year", "what did I watch in March", and an honest streak are all permanently unanswerable once the dates are not kept. The cheaper option is cheap precisely because it throws away the data that makes the feature worth having.
- **`playCount` plus `firstSeenAtEpochMs`.** Fixes the streak and keeps the ranking. Rejected for the same reason, one step later: it preserves the first and last viewing and discards every one in between.
- **Deriving `seen` from the play table.** Conceptually cleaner — one representation instead of two. Rejected because `seen = 0` is itself the state change sync replays; a derived boolean has no row to mark dirty.

## Consequences

- Schema version 7. `verifyMigrations` covers the chain; `EpisodePlayMigrationTest` covers a populated v6 database surviving the upgrade with its history intact.
- `ProgressApi` grew considerably — plays, bulk marks, and their reversals. `ProgressMutations` had to be split into `ProgressPlayMutations` and `ProgressBulkMutations` to stay inside detekt's parameter budget.
- Plays are **exported** but **not synced**. v1 is local-only, and append-only rows need a different conflict rule than ADR 0009's last-write-wins — last-write-wins on a row that is never updated is meaningless, and the merge is a union, not a comparison. Leaving them out of the export instead would silently lose a person's rewatch history on reinstall, which is a different question from replication. Wiring sync later costs the six touchpoints ADR 0009 enumerates.
- A "most rewatched" feature is now possible; it is deliberately not built here. It was built next, and what a *rewatch* turned out to mean is ADR 0012.
- One viewing per bulk mark: marking a season seen skips episodes already seen rather than replaying them. Catching up is not a claim to have rewatched.
