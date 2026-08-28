# Watch progress is the source of truth; status is derived

Per-episode ticks (and a movie's seen flag) — `WatchProgress` — are the single stored truth. `WatchStatus` is always a pure function of progress plus the show's production status (`WatchStatusCalculator` in `:core:model`), never a separate, independently-editable field. This makes contradictory states unrepresentable (you cannot be "Watched" with unseen aired episodes).

There is no override field. Manual status actions (e.g. "mark all watched") mutate the underlying progress so the two never disagree.

## TV status rules

- `NotStarted` — 0 episodes seen.
- `Watching` — at least one seen, but not all *aired* episodes.
- `Watched` — all aired episodes seen, show still ongoing (production not ended).
- `Finished` — all episodes seen and production has ended.

Movies use only `NotStarted` → `Watched`. `Favorite` is an independent flag, orthogonal to status. When a new episode airs on an ongoing show, aired count rises and a previously-`Watched` show naturally derives back to `Watching`.

## Amendment (2026-08-28, ADR 0011): there is a history log now

This ADR said progress carries no separate history log, and that un-ticking an episode simply removes its contribution. That is no longer true: `episodePlay` records one row per viewing so that rewatching is representable at all. See [ADR 0011](0011-rewatch-play-history.md).

What has **not** changed is this ADR's actual subject. `WatchStatus` is still derived, never stored; `episodeProgress.seen` is still the stored fact status derivation reads; and the play table is written in the same transaction as that column, never independently. The history log sits beside the source of truth rather than becoming a second one.

One rule this ADR implies got stricter rather than looser: a bulk "mark seen" now ticks only episodes that have **aired**. Ticking a currently-airing season's unaired episodes pushed `seenEpisodes` past `airedEpisodes`, which `WatchProgress` `require`s against — so the library screen threw the next time it derived that title's status. This was the same trap ADR 0010's amendment documents for `markPreviousSeen`, reached through a different door.
