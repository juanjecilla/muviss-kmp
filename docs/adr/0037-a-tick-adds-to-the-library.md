# A user's tick adds the title to the library

On Detail, ticking a movie or an episode of a title that is not in the library writes progress and a Play but no `CollectionEntry` (`SetMovieWatchedUseCase` → `setSeen`; `episodePlay` rows). The tick is real, the title is in no list: Library, Progress and WatchNext never show it, and the next tap on "Add to library" derives a status from ticks the user cannot see. Making "Mark as seen" the primary action of Detail (#721) turns this from a corner into the main path.

## Decision

**A tick the user makes adds the title to the library when it is absent.** The use case behind Detail's ticks (primary button, movie toggle, episode, season, show, Watched-again) calls `CollectionApi.add(details)` first, the same call "Add to library" makes (it writes the Snapshot through the repository's `upsertSnapshot`, never `refreshSnapshot`), so the entry carries a fresh Snapshot and a sync stamp. The details are on screen whenever an unsaved title can be ticked.
- **A title the user just removed is not "absent" for this purpose.** Removal is a soft delete, and Detail can still hold a stale `savedCopy` (offline, an old catalog). If a soft-deleted entry exists for the title, a tick **un-deletes it as Undo does** and never calls `add(details)`: a Snapshot rebuilt from a stale catalog could carry fewer aired episodes than the user has seen, which `WatchProgress`'s `require(seenEpisodes <= airedEpisodes)` turns into a crash on the next Library derive. `add(details)` runs only when no row exists, and only with details this screen loaded (never `savedCopy`).

- Only ticks the user makes. An import, a sync pull and a restore never add: they bring progress for titles the other source already decided about, and a pull that created entries would resurrect a title the user removed on another device (Completion draws the same line, CONTEXT.md).
- **Undo reverses the entry only if no later edit depends on it, and only if the tick wrote something.** A bulk mark with nothing left to tick writes no tick, so it changes no membership and offers no Undo: the aired-unseen set is checked before the entry is added. If the user favorites, rates, notes or lists the title between the tick and the Undo, Undo reverses the ticks and leaves the entry, because removing it would hide that edit.
- **Undo removes the entry only if this tick created it.** The use case returns what it did to the entry, `Created`, `Restored` (it un-deleted a soft-deleted row) or `None`; the snackbar's Undo reverses the ticks and then reverses that: `Created` removes the entry, `Restored` soft-deletes it again, so the title is as absent as it was before the action. An entry that existed before the tick is never touched by Undo.
- The use case takes `() -> CollectionApi` and resolves it per call. `ProgressApi` already has peers that reach `CollectionApi`; a constructor edge closes the Koin cycle CLAUDE.md warns about.
- A tick on an episode that aired after the Snapshot was last refreshed would leave `seenEpisodes` above `airedEpisodes`. The tick therefore also raises the Snapshot's `airedEpisodes` to the larger of the seen count and the aired count of the catalog the tick used (`EpisodeOrdering.airedBy` over the cached `episode` rows, never lowering a newer stored count), so a Snapshot that says 1 aired against a catalog of 10 does not read as caught up after ticking episode 2, and `WatchNextUseCase` keeps the show. A test pins that case, in the same transaction, the `raiseAiredToSeen` reconciliation a sync pull already runs, so it holds offline too.
- Accepted side effects: the Notification Nudge fires on a first saved show; the tick can be a Completion (celebration, "What's next?" sheet, the #410 review prompt) exactly as a tick from Progress already can.

## Why not the alternatives

- *Hide ticks until saved.* Leaves orphan progress already in users' databases and makes the primary button two taps on an unsaved title.
- *Reconcile on read* (derive membership from ticks). Membership is a user decision with a Snapshot and a sync identity; deriving it would invent entries on every device.
- *Auto-add on every progress write.* Changes sync and restore semantics for no user benefit.

## Consequences

`WatchStatus` stays derived (ADR 0005); nothing stores a status. Remove-from-library gets its own Undo that **restores** the soft-deleted row. `upsertSnapshot` already keeps the favorite flag, rating, note and `addedAt` when it re-adds a removed title, so that is not the reason; the reason is that a re-add rebuilds the Snapshot's aired and total counts from whichever details are on screen, and offline those can be a stale `savedCopy`. A restore (`CollectionApi.restore(mediaId)`, new) keeps the stored Snapshot as it was. The primary "Add to library" uses it too whenever a soft-deleted row exists, so remove then Add offline cannot rebuild from a stale catalog either.
