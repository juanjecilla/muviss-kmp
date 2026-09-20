# Sync pages by a server-assigned sequence, not by client clocks

EPIC 39 (issue #85). ADR 0009 designed sync around last-write-wins on each
row's own `updated_at_epoch_ms` and a pull cursor that was "the newest
timestamp the server has shown me". ADR 0018 gated it and fixed the first
round of bugs. Nothing had run it against more than a handful of rows or more
than one honest clock, and the owner reported that data after syncing "is not
well done". An audit confirmed twelve defects, four of them silent data loss.
This records the decisions that fixed them and the ones that were deliberately
not taken.

Sync has never shipped (it is gated off in every release build), so the server
schema and the client schema could both change without a compatibility story.

## The cursor is the server's sequence, not a maximum of client clocks

The old pull was `GET ...?updated_at_epoch_ms=gt.<cursor>`: no `order`, no
`limit`, one cursor for all six tables, and the cursor advanced to the newest
`updated_at_epoch_ms` in whatever came back. Every part of that was wrong, and
the parts fed each other:

- **Truncation.** Supabase cuts a response at `max_rows` (1000 in
  `config.toml`, unknown on the hosted project) with a `200` and no marker. The
  cursor then jumped to the newest timestamp across all six tables and the rows
  that were cut off were never asked for again.
- **Late writes.** An offline edit pushed after another device's cursor moved
  past its timestamp is older than that cursor, so it is never pulled.
- **Fast clocks.** One device with a clock a day ahead stamps rows a day ahead.
  Every cursor jumps there and stays there, and last-write-wins then rejects
  honest edits to those rows.
- **Ties.** Rows stamped in one millisecond and pushed in separate requests
  (push sends one request per table) are split by the `gt` boundary.

All four are the same mistake: using one device's clock, or a maximum of
several, as a position in a change *feed*. A feed position has to be assigned
by the thing that orders the changes, so every accepted write takes the next
value of one Postgres sequence, `sync_change_seq`, in a `server_seq` column,
and clients page by `order=server_seq.asc&limit=500&server_seq=gt.<n>`.

`updated_at_epoch_ms` keeps its other job, the last-write-wins tiebreak between
two devices' edits of one row. A device's clock still decides *who wins* a
conflict; it no longer decides *what gets delivered*.

**Why not fix the clocks instead.** NTP on the client, or trusting the server's
clock for stamps, would each need every write path in the app to change and
still leave the boundary and truncation cases. Two devices' clocks disagreeing
by seconds is normal and cannot be engineered away; a design that is only
correct when they agree is not correct.

**Why not a per-row version.** It would give the tiebreak a better basis than a
clock, but it needs a read before every write, which is the round trip ADR 0009
chose the trigger to avoid. Out of scope; noted as the honest next step if
whole-row last-write-wins ever proves too blunt.

### One sequence, one cursor per table

The sequence is shared by all six tables; the client stores **one cursor per
table** (`syncCursor`). Sharing means a value is never handed out twice, and
per-table cursors mean draining one table never advances another past rows it
has not seen. A client compares cursors only within a table.

### The trigger stamps, discards and clamps

`discard_stale_write()` now runs on insert as well as update and does three
things in order:

1. **Clamp** `updated_at_epoch_ms` to server time plus 60 seconds. A fast clock
   wins for at most a minute instead of forever.
2. **Discard a stale write** (strictly older than the stored row) by returning
   `old`, exactly as before. Returning `old` also keeps the old `server_seq`,
   which is required: a discarded write must not appear in anyone's feed as a
   change, or every device re-pulls it forever.
3. **Stamp** an accepted write with `nextval`. This overrides whatever the
   client sent, so a client cannot place a row behind another device's cursor.

An upsert onto an existing row fires the insert trigger and then the update
trigger, so it draws two values. **Gaps are normal**; nothing may assume the
sequence is dense.

**Accepted limitation.** A sequence value is drawn when a statement runs and the
row becomes visible when its transaction commits. Two concurrent transactions
can therefore commit out of sequence order, and a client that pulls in the gap
advances past a row that then appears behind its cursor. The window is tiny for
one person's devices. It is bounded rather than eliminated by
`SyncEngine.resyncEverything()` (a full pull, every row marked dirty, a full
push), which EPIC 40 also schedules weekly.

## Pull is paged, and stops on an empty page

`SyncBackend.pull(after, onPage)` streams pages per table with an **opaque**
`SyncCursor`; the engine stores and returns cursors and never interprets one.
The Supabase implementation loops until a page comes back **empty**, not until
it comes back short: `max_rows` makes a short page ambiguous, and the hosted
project's value is not known. It costs one empty request per table per sync and
is correct whatever the cap is. It also verifies the returned order rather than
trusting it, because a server that ignored `order` would hand back an arbitrary
subset and taking its highest `server_seq` as the cursor would skip the rest
for good.

`server_seq` is read from the raw JSON, not decoded into the change types, so
those types and the wire format the rest of the app builds by hand gain no
field.

### Cursors advance only after everything drained and reconciled

The plan said "after a table is fully drained". It is stricter than that: all
cursors move together, in the last transaction, after every table has drained
and the post-pull reconciliation has committed. If the process dies anywhere
earlier no cursor has moved and the retry re-applies the same rows to the same
result. The cost is that an interrupted first sync of a huge library restarts
its download; a resumable pull would need reconciliation to be resumable too.

## Push sends nulls, in chunks, and clears dirty conditionally

- **Nulls.** PostgREST builds `INSERT ... ON CONFLICT DO UPDATE SET c =
  EXCLUDED.c` over the union of the keys in the body. A key absent from every
  object in a batch is not in the statement, so the stored column is left as it
  was. The shared client has `explicitNulls = false` (right for TMDB), so a
  cleared rating, note, poster, year or runtime was never sent and came back
  from the server on every other device. Sync now serialises push bodies with
  a `Json` it owns, `explicitNulls = true`. `:core:network` is not touched.
- **Chunks.** 500 rows per request, parents before children, and the dirty flag
  is cleared per chunk, so a failure at chunk seven leaves one to six sent.
- **Conditional `clearDirty`.** Every `clearDirty` gains `AND updatedAtEpochMs =
  :pushedAt`. The push is an awaited network call and the user can edit the row
  while it is in flight; clearing by key alone marked that edit as sent. A
  same-millisecond second edit is still indistinguishable (the same limit the
  derived play id already has, ADR 0013).

## Who wins a merge

A pulled row replaces a local one when the local row is **clean**, or when it is
strictly newer than a local row with unsent edits. Last-write-wins is a contest
between edits; a clean local row is not one. It is a copy of something the
server already holds, so the server's current version is the truth.

This is what lets the clamp exist. Without it, a fast device's row is clamped on
the server, another device edits it honestly a few minutes later, and the fast
device ignores that edit forever because its own stamp is a day ahead.
Two existing tests asserted that a *clean* local row with a newer stamp beats an
older pulled one. That is a state the engine cannot reach (a row is clean
because it was pushed or pulled), and they had only kept passing because the
first sync now marks every row dirty. They now seed unsent edits, which is the
case they meant.

### User fields and snapshot fields

`collectionEntry` mixes what the user said (favorite, rating, note, tombstone,
add date) with what the provider said (title, poster, episode counts). A pulled
row that wins takes the user fields only; snapshot fields are used only when
there is no local row. A snapshot refresh is no longer a synced write: it leaves
`isDirty` and `updatedAtEpochMs` alone and only touches a live row. Before this,
a device that merely looked at the Library stamped every row `now` and dirty,
beat other devices' real edits, un-deleted titles removed elsewhere, and dirtied
the whole library on every visit. `CollectionRepository.upsertSnapshot` remains
the add path (and revives a tombstone); `refreshSnapshot` is the refresh.

## Two invariants the merge must not break

The rest of the app assumes both, and they are not the merge's to weaken:

- **`seen <= aired`** (`WatchProgress` throws). `collectionEntry` and
  `episodeProgress` merge independently, so ticks can arrive ahead of the
  snapshot that covers them. After each page, in its transaction, `airedEpisodes`
  is raised to the number of episodes ticked (and `totalEpisodes` with it),
  without dirtying the row. A snapshot refresh applies the same floor.
- **`seen` and `episodePlay` agree.** They are written together locally and
  merged separately, so a tick on one device and an untick on another left
  `seen = 0` with a live play, which `recordPlaysForUnseen` then skips, so the
  title could never reach Watched. After every table has drained, for the
  titles the pull touched: a tick with no live play gets one at the tick's own
  timestamp (derived id, so two devices repairing it write the same row), and an
  untick tombstones the plays of that episode. The un-tick wins because it is
  the state `seen` propagates. Duplicate plays from two devices ticking the same
  episode are indistinguishable from a real rewatch and are left alone.

It cannot run per page: `episodeProgress` and `episodePlay` arrive in different
tables at different times, and reconciling after the first would see ticks with
no plays only because the plays had not arrived yet.

Each page is applied in **one transaction**, so Flows re-emit once per page and
no half-applied page is visible (it used to be one select and one upsert per
row with no transaction).

**Not built: a "queue a snapshot refresh" seam.** The plan had the engine queue
a refresh for touched titles. The Library already refetches every saved title on
each visit, so a queue would have no consumer; the aired floor is what protects
the invariant at the moment of the pull.

## Whose library this is

Nothing recorded which account the local rows belonged to, so signing out and
into another account pushed the first account's unsent rows into the second and
merged the second's clean rows with the first's. `syncState.ownerAccountId`
records it:

- **First sync from a database with no owner** adopts the signed-in account,
  marks every local row dirty and resets the cursors: one full reconcile. This
  is also how every existing install migrates (it has no cursors and no owner),
  and how rewatch history from before plays synced finally reaches the server:
  `7.sqm` carried `isDirty` across unchanged and those ticks had long been
  pushed, so their plays were clean and would never have been sent.
- **A different account** returns `SyncOutcome.AccountChanged` and moves nothing.
  `resolveAccountChange(DiscardLocalData | MergeLocalDataIntoAccount)` then acts.
  Discarding deletes the user tables and keeps the `episode` catalog, which is
  TMDB's data (ADR 0015).
- **Plain sign-out** keeps the owner, so signing back in as the same person
  resumes.

This builds the mechanism. Whether discard is the default, what the confirmation
says and what account deletion does belong to EPIC 32 (ADR 0019); until then
Profile shows an `AccountChanged` as a failed sync with a message that says why.

## Other fixes

- A token refresh clears the session only when GoTrue rejects it (400 or 401).
  A dropped connection or a 5xx says nothing about the token, and signing the
  user out for it would have made every offline stretch a forced re-login.
- `CancellationException` is rethrown, not reported as `Failed`.
- Widgets are refreshed after a pull that applied rows.
- `syncState` records the outcome of the last attempt (success or failure, the
  message, the time, consecutive failures) so an unattended run leaves a trace.

## Migration

One migration adds `syncCursor`, `syncState` and `appSettings.syncAutomatically`
(the per-device switch EPIC 40 reads; nothing reads it here). It is `9.sqm`
(schema 10) only because this base had no `9.sqm` or `10.sqm`; `docs/EPICS.md`
claims `9.sqm` for EPIC 26, `10.sqm` for EPIC 28 and `11.sqm` for this. Whoever
merges first keeps the number and the others rename, regenerate the fixture and
re-run `verifyMigrations`. `appSettings.syncCursorEpochMs` is dead and left in
place; dropping it needs a table rebuild for nothing.

The server side is `supabase/migrations/20260920000000_sync_server_seq.sql`. Its
pgTAP tests and the live scripts in `scripts/sync/` have **not been run**.

## Consequences

- A pull is correct for any `max_rows`, and no longer depends on any device's
  clock being right.
- An upgraded install does one full reconcile on its first sync. It is
  idempotent, and slow in proportion to the library.
- The wire format gained a server-owned column the client reads but never sends.
- Real Supabase behaviour that this repo models by hand in `FakeSupabaseServer`
  (column-union upserts, `max_rows`) is verified only by
  `scripts/sync/verify-null-clearing.sh` and `verify-pagination.sh` against the
  owner's project (issue #88). Until they are run, those two assumptions are
  documented PostgREST behaviour, not observed behaviour.
