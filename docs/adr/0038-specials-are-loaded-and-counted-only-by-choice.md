# Specials are loaded, and counted only by choice

Issue #938. Supersedes the "Loading specials reconciles the Snapshot" and "Existing specials must not alert" corner cases of `docs/proposals/detail-primary-action.md`.

## Decision

A show's season 0 (**Specials**, CONTEXT.md) is loaded from TMDB and shown under its own header, with each special tickable. It is **not counted** in a title's progress unless the person turns on "Count specials toward progress" in Settings. The setting is per device, off by default, and is applied at **read time only**.

- **Loading.** `TmdbProvider.details` makes one extra request, `/tv/{id}/season/0`, and only when TMDB's `seasons` list contains season 0. It is not appended with the numbered seasons. A failure fails the detail load like any other season: a refetch replaces a title's stored catalog wholesale, so a partial catalog would silently drop specials the user had seen.
- **Never in the snapshot.** `CollectionEntry.airedEpisodes` and `totalEpisodes` are the regular seasons only, whatever the setting. The snapshot is synced and exported, and whether specials count is a per-device choice, so no refresh, pull or restore writes a special into it.
- **Read-time counting.** The setting decides whether a title's status and Library, Watch Next and Detail counts add the aired specials. `SpecialsCounting` (`:core:model`) is the one function all three call. The Library and Detail read it in the same query (`selectAllWithSeen`, `selectByIdWithSeen`), and Watch Next combines the flag in Kotlin.
- **Only aired, cached specials count.** A special's seen count is a join on the aired catalog, so a tick counts only when its episode is also counted as aired. That keeps `WatchProgress`'s `seen <= aired` true with an empty catalog cache: an upgraded install has no `episode` rows until the app runs once.
- **Season 0 never alerts.** New-episode alerts compare the provider's aired count with the stored one. Specials are in neither, so a special cannot raise one. No baseline migration is needed.
- **Specials are never "next"**, and triage never ticks one. Bulk marks from Detail (a season, or the whole show) do tick aired specials, because the person can see them there.
- **Upgrade (`33.sqm`).** An install that already has a seen special tick (season 0, episode 1 or later) gets the setting **on**; every other install gets it off. Before this release no UI could tick a special, so an existing special tick is the only sign the person already counted specials, and the setting stays on so nothing they had counted stops counting. A movie's single element is also season 0, but its episode is 0, so a movie tick does not turn it on, and an un-tick is not a tick. Ticks are never deleted.
- **Later imports do not turn it on.** A Trakt or other import that writes season-0 ticks into an install with the setting off keeps the setting off, so those ticks are stored but do not count until the person turns the setting on. Settings says so in the row's helper copy. The stored ticks are kept, never deleted, so turning the setting on later counts them.
- **Profile stats keep counting every seen row, whatever the setting.** Hours watched, watch streaks and the most-rewatched list describe what was watched, not progress. A special's viewing is a Play and counts in them (test: `profile_stats_count_a_special_tick_whatever_the_setting`). Only progress reads (status, Library and Watch Next counts, Detail's primary action) follow the setting.
- **The aired day is read per emission.** The Library and Detail queries re-run at each local midnight, so a special that airs at midnight counts without waiting for a write. They re-run once a day per subscriber, not per tick.

## Why not the proposal's reconciliation

The proposal raised the snapshot's aired count when specials loaded, so the count would be right without the setting. That writes a per-device choice into a synced, exported row: a device that counts specials would push a higher count that another device, which does not, would then carry. It also needs a baseline migration to keep old alerts quiet. Counting at read time keeps the snapshot a fact about the regular episodes and makes the setting reversible without a write.

The consequence is that `raiseAiredToSeen` (run after a sync pull or restore) and the snapshot-floor count in `upsertSnapshot` count regular ticks only. A special tick can never raise the snapshot, so the reconciliation that exists for regular ticks is not the clamp for specials; the read-time join is.

## Consequences

- The Detail primary action reads "Caught up" with an unseen aired special while the setting is off. "Special unseen" (#797) appears only with it on, where the show really is Watching. The Specials header says how many aired specials are still unseen, in both states.
- The setting is not in the Display Preferences group (ADR 0036) and is not synced or exported. It may join that group later if people expect it to follow them; that would be a new decision and a backup-format change.
- A title whose only unseen aired episodes are specials is left out of Watch Next when the setting is on, as #797 does today; #842 (a row for that state) stays held.
- Triage's caught-up verdict does not tick aired specials, so with the setting on it can leave a show Watching with a special unseen. This is accepted for now and tracked as a follow-up.
