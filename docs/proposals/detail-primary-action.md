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
| show, ongoing, **at least one episode aired** and every aired episode seen, next episode announced | Caught up ✓, subtitle `Next: S3·E1 · 12 Jan` | disabled |
| show, ongoing, **at least one episode aired** and every aired episode seen, no announced next episode | Caught up ✓ (WatchStatus `Watched`, ADR 0005), no subtitle | disabled |
| show, none aired yet | Not aired yet, subtitle first air date | disabled |
| show ended, all seen | Finished ✓ | disabled |
| show ended or canceled, every **aired** episode seen, unaired episodes remain | Finished ✓, subtitle `N not yet aired` (`WatchStatusCalculator` returns `Finished` once every aired episode is seen; the state is defined by the aired count, not by "all seen") | disabled |

Icon row (outlined): favorite, bell (saved only), add to list, and a new **bookmark** (filled when saved) that removes from the library. Removal gets an Undo that restores the soft-deleted `CollectionEntry` as it was (`CollectionApi.restore`, new), so favorite, rating, note, `addedAt`, list membership **and the stored Snapshot counts as a floor** come back (restore reconciles `airedEpisodes` upward against the available catalog, per ADR 0037, so a show whose next episode aired meanwhile is not left `Watched` and absent from What's next). Undo never rebuilds the Snapshot from the details on screen: offline, `DetailViewModel.savedCopy` can hold a stale catalog, and a rebuilt Snapshot could put `seenEpisodes` above `airedEpisodes`, which `WatchProgress`'s `require` turns into a crash on the next Library derive. Today tapping "In Library ✓" removes instantly with no confirmation and no Undo.

"Mark whole show as seen" and the season checkboxes stay where they are (bulk actions, aired episodes only, `EpisodeOrdering.airedBy`).

## Corner cases
- **Specials must be loadable.** `TmdbProvider.tvDetails` excludes season 0, so a successful TMDB detail load cannot show or tick a special and the "Special unseen" state below would be unreachable. #721 loads season 0 with its own `/tv/{id}/season/0` request, made only when the show's `seasons` list contains season 0 (one extra request on top of `1 + ceil(seasons / 20)`), stores its rows in the `episode` cache table, and shows them under the Specials header. `EpisodeOrdering` keeps skipping them in the next-episode query, so the primary button's behavior is unchanged.
- **Only an unseen special remains.** When every regular episode is seen but an aired season-0 special is not, the next-episode query returns none (specials are skipped) while `WatchStatus` still counts the special, so the show is `Watching`. State: primary button disabled "Caught up ✓" is wrong there; it reads **"Special unseen"** (secondary action: scroll to Specials), and the specials season stays expandable in the list so the user can tick it.
- **Next episode of a saved, unstarted show.** WatchNext is "part-way through", so S1·E1 is not in it. Add a per-title next-episode query on `ProgressApi` that reuses `WatchNextUseCase`'s ordering; do not write a second collection × catalog × ticks join (CLAUDE.md; #600 already objects to Detail reading the whole library). Season-0 specials are never "next".
- **Show with 0 aired episodes**: today "Mark whole show" is a silent no-op after a confirm. Disable it and say why.
- **Unaired episode positioned before the next aired one**: next = first *aired* unseen, never an unaired one (`WatchProgress` `require(seen <= aired)` throws).
- **Rewatch**: a seen movie's primary opens Watched-again; it must never add a second entry or tick twice.
- **Episode catalog unavailable**: a saved show opened offline with an empty local catalog, whose Snapshot says episodes have aired and some are unseen, has no next episode to name and fits no row above. State: primary disabled, `Episodes unavailable offline`, with a Retry that refetches the catalog; test it offline.
- **Sync pulls use the union too.** `raiseAiredToSeen` (run after a pull inside `RemoteApplier`) compares only the seen *count*: with four locally known aired episodes and two more seen ids pulled from another device it can leave `airedEpisodes` at four, so Library reads `Watched` and What's next omits the show, and a failed post-pull refresh keeps that result. It is changed to the same union of known aired ids and seen ids, never lowering a stored count, and a test pulls extra seen ids against a four-episode local catalog.
- **Add with existing ticks.** A title with no collection row can already hold orphan or synced ticks (ADR 0037). If they exceed the aired count of the displayed details, `upsertSnapshot` would write a count below `seenEpisodes` and the Library derive throws, so Add reconciles the new Snapshot with the existing tick ids (the same union rule as below) in the same transaction, before the entry becomes visible. A test adds with orphan ticks exceeding the displayed aired count.
- **The notification baseline is separate from the reconciled count.** `NewEpisodesCalculator.diff` compares the provider's count with `CollectionEntry.airedEpisodes`, so raising that field on a tick would raise the baseline and suppress the alert for the other newly aired episodes (ticking one of three leaves two unannounced). The entry therefore gets its own baseline column (`notifiedAired`, written only by add and by the new-episode check, never by a tick; per device, outside the sync change set and the export; a migration whose number is claimed when the branch opens), `NewEpisodesCalculator` compares against it, and a test ticks before a notification refresh.
- **Newly aired episode**: ticking an episode that aired after the Snapshot was last refreshed raises the Snapshot's `airedEpisodes` to the larger of the seen count and the aired count of the available catalog, never lowering a newer stored count, in the same transaction (`raiseAiredToSeen` is the floor; the aired count is the size of the **union of known aired episode ids and seen ids**, not a comparison of counts, because synced seen ids can be missing from a stale catalog: with 4 aired catalog episodes and 2 seen ids absent from it, ticking two catalog episodes makes the union 6, not 4, so the two remaining catalog episodes stay unseen and the show stays out of `Watched`; the catalog count used for new-episode notifications is left unchanged; a test covers that offline case; **`refreshSnapshot` keeps the reconciled count**: it floors the provider's aired count at the same union, so a later refresh cannot lower 6 back to 4 and mark the show `Watched` while two catalog episodes are unseen, and a test refreshes after the offline case; a seen-only floor would report `Watched` and drop the show from What's next while a later aired episode is unseen), or the next Library derive throws; test it, offline too.
- **Bulk mark with nothing to tick**: the aired-unseen set is checked first; empty means no membership change and no Undo.
- **Edit between tick and Undo**: a favorite, rating, note or list change on the title makes Undo reverse the ticks only; the entry stays.
- **Remove then Add offline**: restores the stored row; `add(details)` only runs when no row exists and the details were loaded by this screen.
- **Zero aired episodes** takes precedence over both caught-up rows: "every aired episode seen" is vacuously true, but the status is `NotStarted` (`WatchStatusCalculator`), so the button reads Not aired yet.
- **Tick on unsaved title** (ADR 0037): the use case result is `Created`, `Restored` or `None`. Undo reverses the entry only if this tick wrote it, and only when no later edit depends on it: `Created` is removed again, `Restored` is soft-deleted again. Tests: add → tick → Undo, and remove → tick → Undo.
- **Tick on a title just removed** (stale `savedCopy` still on screen, possibly offline): a soft-deleted row is un-deleted, never re-added from `savedCopy` (ADR 0037).
- **Completion on the primary tick** fires celebration, "What's next?" and the review prompt like a Progress tick.
- **Remove while a tick Undo is showing**, double tap, remove then Undo then tick: each action replaces the snackbar slot; the keyed-effect pattern from #569/#613 applies.
- **Stale network**: all local; Snapshot comes from the details already on screen.
- **Wide layout (≥840 dp)**: the stacked `ActionRow` under the poster keeps the same states.
- **Accessibility**: the label includes the episode ("Mark season 2 episode 3 as seen"); disabled states expose a reason (#459).

## Files
`DetailScreen.kt` (`ActionRow`, `MovieWatchedToggle`, `MarkShowSeenButton`), `DetailViewModel.kt` (`toggleSaved`, `toggleMovieWatched`, state), `ProgressApi` + `ProgressUseCases.kt` (next-episode query, tick-adds use case), `CollectionApi` (un-delete), `strings.xml` en/es.

## Tests
ViewModel: remove then tick while offline un-deletes the row and keeps the stored counts; remove then Add while offline restores the stored row; a newly aired episode ticked offline keeps seen <= aired; an empty bulk mark adds nothing and offers no Undo; a favorite set before Undo keeps the entry; the unavailable-catalog state and its Retry; remove then Undo while the details request fails keeps the stored counts (seen never exceeds aired, status still derives); created-entry Undo, pre-existing-entry Undo, 0 aired, caught up, ended, unaired-before-next, remove + Undo keeps favorite/rating. Goldens `detail-show*` re-recorded; StoreShot and fastlane/website screenshots refreshed (#707 flakes the seasons capture). Landing `en.ts`/`es.ts` and the Play description (free, user-visible `feat`).
