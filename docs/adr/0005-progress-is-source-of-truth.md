# Watch progress is the source of truth; status is derived

Per-episode ticks (and a movie's seen flag) — `WatchProgress` — are the single stored truth. `WatchStatus` is always a pure function of progress plus the show's production status (`WatchStatusCalculator` in `:core:model`), never a separate, independently-editable field. This makes contradictory states unrepresentable (you cannot be "Watched" with unseen aired episodes).

There is no override field. Manual status actions (e.g. "mark all watched") mutate the underlying progress so the two never disagree.

## TV status rules

- `NotStarted` — 0 episodes seen.
- `Watching` — at least one seen, but not all *aired* episodes.
- `Watched` — all aired episodes seen, show still ongoing (production not ended).
- `Finished` — all episodes seen and production has ended.

Movies use only `NotStarted` → `Watched`. `Favorite` is an independent flag, orthogonal to status. When a new episode airs on an ongoing show, aired count rises and a previously-`Watched` show naturally derives back to `Watching`.
