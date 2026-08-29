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
