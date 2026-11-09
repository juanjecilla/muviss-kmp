# Detail: the primary action follows the library state

Issue #721. Decision record: ADR 0037.

## Problem
`ActionRow` (`feature/search/ui/.../DetailScreen.kt`) shows one filled button, "Add to Library" / "In Library ✓". Seen-marking is elsewhere: `MovieWatchedToggle` sits below the overview; a show's `MarkShowSeenButton` is a `TextButton` in the seasons header with a confirm dialog. For a title in the library, marking seen is the thing people open Detail to do.

## Proposal
One filled, full-width button whose meaning follows the state:

| State | Label | Tap |
|---|---|---|
| not in library | Add to library | add (`upsertSnapshot`) |
| movie, unseen | Mark as watched | tick, Undo snackbar |
| movie, seen | Watched ✓ (· N×) | Watched-again dialog (existing) |
| show, next aired episode exists | Mark S2·E3 seen, subtitle: its name | one Tick, Undo |
| show, nothing aired unseen, more announced | Caught up ✓, subtitle `Next: S3·E1 · 12 Jan` | disabled |
| show, none aired yet | Not aired yet, subtitle first air date | disabled |
| show ended, all seen | Finished ✓ | disabled |

Icon row (outlined): favorite, bell (saved only), add to list, and a new **bookmark** (filled when saved) that removes from the library. Removal gets an Undo that un-deletes the soft-deleted `CollectionEntry`, so favorite, rating, note, `addedAt` and list membership come back. Today tapping "In Library ✓" removes instantly with no confirmation and no Undo.

"Mark whole show as seen" and the season checkboxes stay where they are (bulk actions, aired episodes only, `EpisodeOrdering.airedBy`).

## Corner cases
- **Next episode of a saved, unstarted show.** WatchNext is "part-way through", so S1·E1 is not in it. Add a per-title next-episode query on `ProgressApi` that reuses `WatchNextUseCase`'s ordering; do not write a second collection × catalog × ticks join (CLAUDE.md; #600 already objects to Detail reading the whole library). Season-0 specials are never "next".
- **Show with 0 aired episodes**: today "Mark whole show" is a silent no-op after a confirm. Disable it and say why.
- **Unaired episode positioned before the next aired one**: next = first *aired* unseen, never an unaired one (`WatchProgress` `require(seen <= aired)` throws).
- **Rewatch**: a seen movie's primary opens Watched-again; it must never add a second entry or tick twice.
- **Tick on unsaved title** (ADR 0037): entry created first; Undo removes it only if created by this tick.
- **Completion on the primary tick** fires celebration, "What's next?" and the review prompt like a Progress tick.
- **Remove while a tick Undo is showing**, double tap, remove then Undo then tick: each action replaces the snackbar slot; the keyed-effect pattern from #569/#613 applies.
- **Stale network**: all local; Snapshot comes from the details already on screen.
- **Wide layout (≥840 dp)**: the stacked `ActionRow` under the poster keeps the same states.
- **Accessibility**: the label includes the episode ("Mark season 2 episode 3 as seen"); disabled states expose a reason (#459).

## Files
`DetailScreen.kt` (`ActionRow`, `MovieWatchedToggle`, `MarkShowSeenButton`), `DetailViewModel.kt` (`toggleSaved`, `toggleMovieWatched`, state), `ProgressApi` + `ProgressUseCases.kt` (next-episode query, tick-adds use case), `CollectionApi` (un-delete), `strings.xml` en/es.

## Tests
ViewModel: created-entry Undo, pre-existing-entry Undo, 0 aired, caught up, ended, unaired-before-next, remove + Undo keeps favorite/rating. Goldens `detail-show*` re-recorded; StoreShot and fastlane/website screenshots refreshed (#707 flakes the seasons capture). Landing `en.ts`/`es.ts` and the Play description (free, user-visible `feat`).
