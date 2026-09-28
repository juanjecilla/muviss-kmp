# What counts as a rewatch

ADR 0011 built `episodePlay` — one row per viewing — and said in as many words that a "most seen" ranking was now possible and deliberately not built. This is that feature, and the questions it had to answer turned out to be about meaning rather than storage: the table holds viewings, and *rewatch* is not a synonym for *viewing*.

## Decision

**A rewatch is a play that has an earlier play of the same element behind it, at any date ever.** A title's score is its rewatches — plays beyond the first, per element. A show counts episode rewatches; a film counts viewings after the first.

That definition is what makes the ranking mean anything. Ranking by raw play count ranks by library size: a 200-episode sitcom watched once scores 200 and a film watched five times scores 5. Under this definition a first watch-through scores zero however long the title is, and what is left is what a person would call *coming back to something*.

**A time window bounds the rewatch, never the first play.** "This year" filters the timestamps of rewatch plays; which plays *are* rewatches is decided against all of history. An episode first seen in December 2025 and watched again in March 2026 is a rewatch in March. This costs a CTE joining every play against its episode's earliest — over all time the same result is `COUNT(*) - COUNT(DISTINCT episodeId)`, so the CTE buys nothing except correctness under a window, which is exactly what it is for.

**The two lists stay apart and the unit is always printed** — "41 episode rewatches", "5 rewatches". Shows and films are counted in different units and are not made comparable by arithmetic.

**The ranking is a view of the library; the trend is a view of history.** A title removed from the collection leaves the ranking, because the ranking is the library sorted by rewatches and the title is not in the library. Its plays are untouched on disk (only `clearForMedia` erases those), so the number returns intact if it is saved again, and the trend chart keeps counting them.

**The chart's window is fixed at the trailing twelve months** and says so in its own heading, while the lists follow the toggle.

**The profile card renders even when empty**, teaching the "Watched again" gesture.

## Considered options

- **Window-local first play** — recompute "first" inside the window: one `GROUP BY`, no CTE, and the cheapest thing that could work. Rejected because it answers a different question and looks like it answered ours: that March 2026 viewing has exactly one play in 2026, that play is its own first, and the year reports zero. Every January the metric would forget what you had already seen. A wrong number that renders is worse than a missing one.
- **Rank by raw plays, one merged list** — literal, no derived concept. Rejected for the length bias above; it is a "most episodes watched" ranking wearing a rewatch label.
- **Weight by runtime**, scoring hours re-viewed so a 22-minute sitcom and a 60-minute drama compete evenly. Genuinely better on its own terms and rejected on data quality: `runtimeMinutes` is a nullable TMDB snapshot field, and `ProfileStatsCalculator` already substitutes 120/45-minute constants when it is missing. The ranking would have been built on those guesses without saying so.
- **Rank shows by complete rewatches** — the minimum play count across a show's episodes, i.e. how many times you have been through the whole thing. The truest reading of "most rewatched show" and comparable across lengths. Rejected because it is zero for every show not finished at least twice, so most libraries would show an empty list, and a single un-ticked episode zeroes a show watched five times.
- **Keeping removed titles in the ranking**, rendered from the soft-deleted snapshot. Rejected: the query would have to read deliberately-deleted rows, and the list would surface titles that exist nowhere else in the app.
- **One window control for the whole screen** — all time as bars per year, this year as bars per month. Coherent, and rejected on the shape of the actual data: the app is months old, so "all time" renders a single bar and a calendar-year chart is mostly empty until autumn. A rolling year is always full and always comparable.
- **Hiding the card until there is something in it.** Tidier, and it would have made the feature invisible: the backfill gave every already-seen episode exactly one play, so every install upgrades with zero rewatches, and the gesture that fills the card is advertised nowhere else.

## Consequences

- **No schema change.** The queries live in `EpisodePlay.sq` and touch that table only; the existing `episodePlay_episodeId` index serves the CTE. Schema stays at version 7 — no `.sqm`, no fixture, no migration for anyone to survive.
- **No window functions.** `minSdk 24` means Android ships SQLite 3.9, and window functions need 3.25. The CTE plus a bare `id` beside `MIN(watchedAtEpochMs)` (SQLite's documented min-row rule) does the same work on every driver, and the bare `id` is what stops two viewings recorded in the same millisecond from cancelling each other out.
- **The chart can total more than the lists below it** when rewatched titles have been removed from the library. That is the visible edge of "the ranking is the library, the trend is history", and it is deliberate.
- `ProgressApi` grew two reads (`observeRewatchCounts`, `observeRewatchTimestamps`) which report every title, library member or not. Filtering to the library is the caller's decision and the profile feature makes it, in Kotlin, over `CollectionSummary` — so the ranking is unit-testable with no database, and no query crosses a feature boundary.
- Months are bucketed in Kotlin through `CivilDate` rather than by SQLite's date functions, which needed the documented inverse `epochDayOfCivil` in `:core:common`. One notion of "what day is it" for the whole app, shared with `WatchStreak`.
- `EpisodePlayMigrationTest` now asserts that an upgraded library reports **no** rewatches at all. If a future migration ever backfilled more than one row per tick, a person's first sight of this feature would be a fabricated history.

## Amendment (2026-09-28, issues #87/#97): a play is not automatically "earlier behind it"

"A rewatch is a play that has an earlier play of the same element behind it,
at any date ever" turned out to have a gap: two devices ticking the same
episode within seconds of each other, unsynced, produced exactly that shape —
one play with an earlier one "behind it" — despite being one viewing, not two.
`RemoteApplier.mergeDuplicatePlays` now removes the near-simultaneous
duplicate during post-pull reconciliation, before this file's queries ever
see it, so the *definition* of a rewatch in this ADR is unchanged — the fix
is that one of the two plays is not really there. See ADR 0013's 2026-09-28
amendment for the merge rule itself and why it lives in `RemoteApplier` rather
than in `rewatchCountsByMedia`.
