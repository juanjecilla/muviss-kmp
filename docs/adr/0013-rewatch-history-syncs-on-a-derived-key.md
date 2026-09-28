# Rewatch history syncs, on a key derived from the viewing

ADR 0011 added `episodePlay` — one row per viewing — and deliberately kept it
off the change-log. `EpisodePlay.sq` said why: *"v1 is local-only, and
append-only rows need a different conflict rule than ADR 0009's
last-write-wins."* This reverses that.

The reason it was true is that the table had no cross-device identity. Its
primary key was `INTEGER PRIMARY KEY AUTOINCREMENT`, allocated by whichever
device happened to record the viewing, so device A's play #5 and device B's
play #5 are different viewings that would collide as the same row. With no
usable key there is nothing for last-write-wins to compare, and the append-only
framing followed from that rather than from anything about append-only data.

Give the row an identity that comes from the viewing instead of from the
device, and last-write-wins applies unchanged.

## Identity is `episodeId@watchedAtEpochMs`

A play is now keyed by a `TEXT` id derived from what it records —
`tmdb:tv:1399/1/1@1735689600000`. Two devices that record the same viewing
derive the same id, which is exactly the property a sync key needs. It also
makes a double push idempotent: the same viewing arriving twice collapses
instead of duplicating history.

The cost is that two viewings of one episode in the same millisecond merge into
one row. That is not reachable by tapping, and the merge is the same behaviour
the migration applies to any such pair already in a v7 database (`GROUP BY`,
rather than aborting the whole migration on a duplicate key).

Considered and rejected: a random UUID per row. It removes the same-millisecond
merge and is the conventional answer, but the `7.sqm` backfill would then have
to generate ids in SQL (`lower(hex(randomblob(16)))`). A nondeterministic
migration means the same v7 database produces a different v8 database on every
run — fine for `verifyMigrations`, which compares schema rather than data, but
it costs reproducibility for no benefit this app can use. It would also make
duplicate pushes create duplicate plays rather than collapsing them.

Also considered: a composite `(episodeId, watchedAtEpochMs)` primary key. Same
merge behaviour with one fewer column, but every other change type in
`SyncChangeSet` is single-string-keyed, and the PostgREST upsert conflict
target would become the only two-column key in the schema.

## Deletes became soft

The three `DELETE` statements are now `UPDATE ... SET deleted = 1`, and every
read filters `deleted = 0`.

Last-write-wins cannot express a hard delete. A physically removed row has no
`updatedAtEpochMs` left to compare against, so the other device's surviving
copy is simply newer than nothing and gets pushed straight back — "clear watch
history" would undo itself on the next sync. A tombstone is just another field
riding the same rule, which is the argument ADR 0009 already made for
`collectionEntry`.

This is the trap to remember when touching this table: a read that forgets `AND
deleted = 0` silently inflates a play count on the detail screen, or adds days
to the profile watch-streak that the user did not watch anything on.

`insert` also became `INSERT OR REPLACE`. With a derived id, two ticks inside
one millisecond now collide on the primary key where they previously produced
two rows, and a plain `INSERT` would throw. Replacing additionally revives an
id that was tombstoned earlier, which is the right outcome — the user is
recording that viewing again.

The id is composed in Kotlin (`SqlDelightProgressRepository.playId`) rather
than in the `.sq` file, because `:episodeId || '@' || :watchedAtEpochMs` binds
one parameter as both TEXT and INTEGER and SQLDelight cannot type that. The
migration composes the same expression in raw SQL; the two must agree.

## Consequences

- `7.sqm` rebuilds the table (SQLite cannot `ALTER` a primary key): create,
  `INSERT ... SELECT` with the derived id, drop, rename, recreate both indexes.
  The explicit `DROP INDEX` before `DROP TABLE` is not needed at runtime —
  SQLite drops a table's indexes with it — but SQLDelight's migration analyzer
  does not model that and reports the recreated names as duplicates.
- Backfilled rows keep their existing `isDirty`. The history 6.sqm synthesised
  from `episodeProgress` ticks is already on the server in that form, and
  re-pushing every upgrading install's entire history is not worth the first
  sync it would cost.
- `episodePlay` is the sixth table in `SyncChangeSet`, and the first whose
  change type carries its `id` explicitly — every other one is keyed by a
  natural column that was already being sent.
- `docs/SYNC.md` gains an `episode_play` table with the same RLS policy and
  `discard_stale_write()` trigger as the rest. An existing project needs that
  SQL run before an upgraded client will sync plays.
- Rewatch counts now differ between devices only until the next sync, rather
  than permanently. Before this, `seen` states matched across devices while
  "watched 3x" markers did not, which read as a bug and was in fact the
  documented design.
- **The same-millisecond collapse interacts with EPIC 21's rewatch stats**,
  which landed on `main` while this was in review. Two plays of one episode at
  one millisecond used to be two rows and one rewatch; they are now one row and
  none. `RewatchQueryTest` asserted the old behaviour and now asserts the new,
  with the boundary case beside it.

  Kept rather than reversed: a person cannot tap twice inside a millisecond,
  and every path that writes several plays at one timestamp writes them for
  *different* episodes, whose ids differ. It is reachable only from a test
  holding a fixed clock. Should that ever stop being true — a bulk import
  stamping many plays of one episode identically, say — the fix is a
  disambiguating suffix on the id, which needs a migration but not a change to
  any of the reasoning above.

  Note that `rewatchCountsByMedia`'s `id` tiebreak is unaffected and still
  needed: it disambiguates two *different* episodes sharing a timestamp inside
  the `MIN()` aggregate, which bulk "mark season seen" produces constantly.
- **Both rewatch queries had to gain `deleted = 0`**, in the CTE and the outer
  query. They arrived from EPIC 21 assuming hard deletes; git merged them
  cleanly against this branch's soft ones and produced statistics that counted
  plays the user had cleared. Filtering only the outer query would be just as
  wrong in the other direction — a deleted first viewing would still anchor
  `firstAt`, so the surviving later play would stop counting as a rewatch.

## Amendment (2026-09-28, issues #87/#97): near-simultaneous duplicate plays merge on pull

Filed twice — #87 and #97 both describe the same gap, left open on purpose
when EPIC 39 wired up sync: two devices that each tick the same episode while
unsynced derive two different ids, because the millisecond in
`episodeId@watchedAtEpochMs` almost never matches between two independent
taps. After sync the episode has two live plays, and `rewatchCountsByMedia`
(ADR 0012) counts the second arrival as a rewatch it never was, inflating the
profile's rewatch ranking and, through it, the watch streak.

**The two issues themselves say why this cannot be solved on the write side.**
A genuine rewatch of the same episode from a second device is byte-for-byte
the same shape as this bug — same `episodeId`, a plausible `watchedAtEpochMs`
— so nothing in the row itself says which case a given pair of plays is. The
decision recorded here is a heuristic, not a derivation: **plays of the same
episode from different devices land within 5 minutes of each other, or they
did not.** Within the window, they are one viewing — keep the earliest,
tombstone the rest. Outside it, both survive and a rewatch weeks or months
later still counts as one.

**Where it runs.** `RemoteApplier.mergeDuplicatePlays`, called from
`reconcile` (EPIC 39, ADR 0020) right after `tombstonePlaysForUnseen` and
before `insertMissingPlaysForSeen`, over the same `mediaIds` a pull touched.
It has to sit in reconciliation and not in `applyEpisodePlay`: a single pulled
page only ever proves one row against local state, and merging needs to
compare live plays of one episode against *each other* — the same reason
`seen`/play reconciliation cannot run per page either.

**Anchored, not chained.** For each episode's live plays, sorted by
`watchedAtEpochMs` (ties broken by `id`, `rewatchCountsByMedia`'s own
tiebreak), a play starts a new anchor unless it falls within 5 minutes of the
*current* anchor — never the previous play. A chained "within 5 minutes of
its neighbour" rule would let a slow trickle of taps, each one just inside the
window of the last, walk a single viewing arbitrarily far from where it
started. Anchoring on the first play of each cluster keeps the window fixed
to one real event.

**Decided in Kotlin, not SQL.** The natural expression of "compare to the
previous row in order" is a window function, and this table already ruled
those out: ADR 0012's "No window functions" consequence records that
`minSdk 24` ships SQLite 3.9 against a 3.25 requirement, on every driver this
table's queries have to run identically on. `mergeDuplicatePlays` reads the
candidates with a plain ordered `SELECT` (`selectLiveForMediaIds`) and walks
them in Kotlin instead, the same trade CLAUDE.md already documents for
`rewatchCountsByMedia`'s CTE.

**Convergence is determinism, not a timestamp race.** Two devices reconciling
the same episode's live plays sort them identically and so pick the same
survivor independently — neither needs to see the other's decision first.
`tombstoneDuplicatePlay` stamps the loser with the merging device's own
`AppClock.nowEpochMs()`, not a `MAX(...)` against anything, because it does
not need to win last-write-wins against a specific value: both devices
converge on `deleted = 1` for that id regardless of whose stamp is larger, and
`episodePlay`'s reads already filter `deleted = 0` (this table's oldest
lesson, restated once more). This is unlike `tombstonePlaysForUnseen`, which
does need its stamp to beat a specific surviving edit and uses `MAX(...)` for
exactly that reason.

**Considered and rejected:**

- **A random tiebreak (lowest id wins, say) instead of earliest-timestamp.**
  Simpler, and wrong: it would sometimes keep the *later* play as the
  survivor, silently moving a viewing's recorded date forward by however long
  the two devices' clocks disagree. Keeping the earliest is also what "one
  viewing" means — the moment it was first watched.
- **Deduplicating by `episodeId` alone**, dropping every play but the first
  ever recorded. This is what #97 explicitly ruled out: it would erase every
  real rewatch, not just near-simultaneous ones.
- **A UI affordance ("merge these plays?") instead of a silent merge.** Two
  plays 90 seconds apart are not a decision a person can usefully weigh in
  on — by the time sync has run, neither device shows anything to react to,
  and the alternative (surfacing every pull-time merge as a notification)
  is a much larger feature for a case that should simply not have happened.
- **A longer or shorter window than 5 minutes.** No data motivated a
  different number; 5 minutes is generous enough to cover "two people picked
  up their own phone to tick the episode they just finished together" while
  being short enough that no plausible rewatch lands inside it by accident.
  If it ever needs tuning, `MERGE_WINDOW_MS` is the one place.

**Consequences.**

- No schema change. `selectLiveForMediaIds` and `tombstoneDuplicatePlay`
  (`EpisodePlay.sq`) read and write through the existing columns; the
  existing `episodePlay_episodeId`/`episodePlay_mediaId` indexes serve both.
- `RemoteApplier` now takes an `AppClock`, its first dependency beyond the
  database. `SyncEngine` already held one for other purposes and passes it
  straight through.
- `SyncIntegrationTest` gained two two-device tests:
  `two_devices_ticking_the_same_episode_minutes_apart_merge_into_one_viewing`
  (the bug, fixed) and
  `two_devices_watching_the_same_episode_weeks_apart_both_count_as_real_viewings`
  (the window's edge, proving a real rewatch is not collateral damage).
- The merge only ever runs over titles a pull actually touched, same as the
  rest of `reconcile` — it costs nothing on a sync with no episode-play
  changes.
