# Display Preferences and Muted Genres sync across devices

`appSettings` is one row of 26 columns, and its comments in `AppSettings.sq` say most of them are per device: motion, triage and snooze choices, notifications, sync, crash reports, backup and the one-time introductions. #512 then exported ten of them with the backup, plus the muted-genre list as one string, restoring each value only while it still held its first-run default. Nothing reaches another device, so a new phone starts from defaults, and a restore cannot tell a value the person chose on purpose from one that was never changed.

Muted Genres (ADR 0031) is a set stored as one `;`-joined string. Synced as one column, last-write-wins on the whole string would lose one device's change to the other's, which is why ADR 0031 and #385 wanted one row per genre. Its defaults also have to stay out of the sync: a fresh device that pushed "Talk, News and Reality are muted" would re-mute titles another device had deliberately unmuted.

This records what crosses the device boundary, how each of the two groups replicates, and the plan. The owner's decisions are taken as given; the calls the owner has not confirmed are marked.

## Decision

### Two kinds of setting

A **Display Preference** is a choice about how the library looks, moves or reads, which the person expects to find the same on every device they sign in to. A **Device Switch** describes this device or this install: its OS permission, background work, crash consent, OS backup, first-run history, or a co-watch default. CONTEXT.md carries both terms.

Every `appSettings` column, and its verdict:

| Column | Verdict | Why |
|---|---|---|
| `id` | key | Singleton row. |
| `theme` | **Display** (synced, exported) | The look of the library. |
| `language` | **Display** | The TMDB language the person reads. `''` still means "this device's system language"; that is a device-relative value, accepted. |
| `region` | **Display** | As `language`. |
| `animationsEnabled` | **Display** (was device) | The person's motion choice. The OS reduce-motion default still applies once per install (`motionDefaultApplied`, #394). A default is never a change, so it is never pushed. |
| `triageDeckAnimations` | **Display** (was device) | Narrows `animationsEnabled`; the three motion switches travel together. |
| `tickAnimations` | **Display** (was device) | As `triageDeckAnimations`. This was the open question in #391 and #392. |
| `triageControlScheme` | **Display** (was device) | Which direction means which verdict is how the deck reads for the person, and #512 already exports it. |
| `triageSnoozePeriod` | **Display** (was device) | A Snooze carries its due date in `triage_snooze`, so the period that produced it belongs with it. |
| `triageSnoozePlacement` | **Display** (was device) | Where a returning card lands is part of how the deck reads. |
| `mutedGenresInSearch` | **Display** | The owner's call: it rides with the group. The genre list itself is not in the group (below). |
| `notificationsEnabled` | Device | The OS grants permission per device (ADR 0029). |
| `lastSyncedAtEpochMs` | Device | This device's last cycle, stamped from its own clock. |
| `syncCursorEpochMs` | Device (dead) | Superseded by `syncCursor` (ADR 0020). Left in place, since dropping it needs a table rebuild. |
| `syncAutomatically` | Device | The per-device background switch (ADR 0021). |
| `crashReportsEnabled` | Device | Consent per install, read before the graph exists. |
| `deviceBackupEnabled` | Device | Governs whether this device's OS backup carries its files (ADR 0034). Restored onto another device it would flip that device's exclusion. |
| `coWatchPoolListId` | Device (**owner to confirm**) | Names the list this device publishes as its Watch Pool. Co-watch's defaults stay per device until co-watch's own sync question is settled. |
| `coWatchIncludeSeen` | Device (**owner to confirm**) | A co-watch session default, applied only where a title has no answer of its own. |
| `triageTutorialSeen`, `triageSnoozeHintSeen`, `discoverIntroSeen`, `onboardingCompleted`, `notificationNudgeDismissed` | Device | One-time marks about this install's first run. A synced "seen" would skip the introduction on a device that never showed it (ADR 0029). |
| `motionDefaultApplied` | Device | Records that this install took its motion default from the OS (#394). |
| `mutedGenres` | **Moved** to its own table | A set, not a value (below). |

Ten columns are Display Preferences (`theme`, `language`, `region`, `animationsEnabled`, `triageDeckAnimations`, `tickAnimations`, `triageControlScheme`, `triageSnoozePeriod`, `triageSnoozePlacement`, `mutedGenresInSearch`). Fourteen are Device Switches. One moves out, and `id` is the key. That is all 26.

The export follows the same split: the Display Preferences and the Muted Genres are exported, and Device Switches never are. A restore never writes a Device Switch.

### The group is one row with one stamp

The Display Preferences replicate as **one row per account**, with one `updatedAtEpochMs` stamp, set by any member's change. The row wins on last-write-wins against another device's stamp, under the same rules as every table: a clean local copy takes the server's, and unsent edits are kept unless the pulled stamp is newer.

Per-field last-write-wins was rejected. It would need ten stamps and ten rows for settings people change together. The cost is accepted and stated plainly: if device A has an unsent change to `language` and device B pushes a newer `theme` first, A's push is discarded as older, and its `language` edit is replaced by B's group. Only offline edits reach this, because a clean device always takes the server copy.

### Defaults are never data

A device's first-run defaults are never written, never pushed and never dirty. Only a change the person makes stamps the group and sets its dirty flag. So a fresh install pushes nothing, and a pull onto a fresh install changes only the group.

An upgraded install that already differs from the defaults is backfilled in `25.sqm` with stamp `0` and dirty set. The stamp is a constant, not the migration's clock, so the same database always produces the same rows (ADR 0013's reproducibility argument). Any real change made later outranks it.

### Muted Genres are decisions over the defaults

This amends ADR 0031's note on #385. That note said a device's first explicit change writes the three defaults as real rows, live or tombstoned. That is withdrawn, for the reason below.

A row in `mutedGenre` is a **decision** on one genre, keyed `(mediaType, genreId)`:

- a live row: the person muted this genre, whatever its default;
- a tombstone: the person unmuted it, whatever its default;
- no row: the default applies (Talk, News and Reality for TV; everything else not muted).

So `muted(g) = row(g)?.let { !it.deleted } ?: default(g)`. A device that never chose has no rows and mutes the defaults. "Mute nothing" is three tombstones for the defaults, and nothing else.

**Why not the full set on the first change.** Suppose the phone unmutes Talk at time t1, and a new tablet later mutes Horror at t2 > t1. If the tablet wrote the three defaults as its first change, it would push a live Talk row at t2 and re-mute Talk on the phone, against a choice the person made on the phone. Under decisions the tablet pushes only the Horror row, and nothing about Talk.

**Why no separate "ever chosen" marker.** Once the decisions carry the choice, a second synced row would only record that a choice exists, and no state needs that. The cost is stated: if the defaults ever change, a device that never chose takes the new defaults (wanted), and a device that did choose keeps its decisions for the genres it decided and inherits the new defaults for those it did not. A default change therefore needs its own decision when it is made. A separate marker would be the fix, and it is one more synced row to add then.

**The read rule.** Every query for the explicitly-muted set filters `AND deleted = 0`. A query that forgets it reads unmute decisions as mutes. This is the same lesson as `episodePlay` and `triageSnooze` (CLAUDE.md).

**Conversion.** `25.sqm` creates the table. The legacy `appSettings.mutedGenres` string is converted in Kotlin on the first open where `mutedGenre` is empty and the string is not the default: a live row for each listed genre, a tombstone for each default the string leaves out, all with `isDirty = 1` and `updatedAtEpochMs = 0`. Parsing stays in `MutedGenresCodec`, because genre names are free text and a SQL split would mishandle them. The string column stays, dead, as `syncCursorEpochMs` does. It is still written into the export, so an older build can read a new backup.

### Wire shape and migrations

- `display_preferences`: one row per account, primary key `user_id`, the group's columns, `updated_at_epoch_ms`, `server_seq`. It has no `deleted` column: the group is never removed, and a person resets a value by changing it.
- `muted_genre`: primary key `(user_id, media_type, genre_id)`, `name`, `deleted`, `updated_at_epoch_ms`, `server_seq`.
- Both are single-owner tables with "own rows only" RLS, the `discard_stale_write()` trigger and a `(user_id, server_seq)` index, the same as `triage_snooze`.
- The Supabase migration timestamps must sort after `20261027000000`, which is taken by #588 (Taste Profile). Claim them when each branch opens.

### Export and restore

- `SettingsExport` keeps its ten fields and gains `displayPreferencesUpdatedAtEpochMs: Long? = null`.
- `MuvissDataExport` gains `mutedGenres: List<MutedGenreExport> = emptyList()`, tombstones included.
- **`formatVersion` stays 2.** Every addition is defaulted, which is the rule since v1. Bumping it would make the v2 builds refuse a new backup with `NewerBackupVersion`, the wrong failure for a restore.
- Restore of the group takes the backup's group when its stamp is newer than the local one. A null local stamp loses to any backup stamp, and ties keep local. The restored group is dirty with the **backup's** stamp, not the current time, so it reaches the account and still loses to anything newer there.
- Restore of muted genres merges per row on `updatedAtEpochMs`, ties keep local, and restored rows are dirty.
- A v2 file has neither the stamp nor the list. It restores as it does today: the group field by field over defaults, and its string through the same conversion.

## Considered and rejected

- **Per-field last-write-wins for the group.** Precise, but ten stamps and ten rows for settings that move together. Its only win is the offline-edit case above, which the owner accepted.
- **The ADR 0031 full set on the first change.** It re-mutes a genre on another device that the person unmuted there, as shown above.
- **A muted list as one synced string.** Last-write-wins on the whole set loses one device's change to the other's.
- **A separate "ever chosen" marker.** Not needed while decisions carry the choice; see above for the trade-off.
- **Bumping `formatVersion`.** It would refuse new backups in old builds.
- **Keeping device-only motion switches.** The OS default already reaches each install, and a person who turned motion off on one device expects it on the others. A pulled value is the person's choice, not a device fact.

## Consequences

- `AppSettings.sq`'s comments for the motion, triage and snooze columns no longer describe them as per-device. They are rewritten in slice #642, with the verdicts above.
- The pinned lists change deliberately: `SyncChangeSetShapeTest` (two new elements), `ExportShapeTest` (the new fields), and `FakeSupabaseServer` (two schemas).
- **No new `FeatureFlags` member.** Sync's existing gates apply, so no fake changes. If one were ever added it would have to be implemented by `AppSettingsFeatureFlags`, `core/testing`'s `FakeFeatureFlags`, and four test-local fakes: `SettingsViewModelTest`, `TriageUiTestFixtures`, the profile `SyncRepositoryFixtures`, and `CrashReportsSettingTest`'s `NoFlags`.
- **Delete all data does not yet stay deleted on a signed-in device.** Its erase does not sign out, so the next sync adopts the account and pulls from cursor zero. This was already true for the library, and it now extends to these settings. Recorded in #645; it is not fixed here.
- ADR 0031's note on #385 is superseded; the rest of ADR 0031 stands.
- Tick animations (#391) now sync; the decision above reverses its "per device, not synced" for the motion switches.

## Schema

**`25.sqm`** (the number is the owner's fixed order: 553 takes 21, 543 takes 22, 588 takes 23, 563 takes 24, this takes 25). It is written in full by the first slice that merges, which is #642, and the later slices do not change it. It contains:

- `ALTER TABLE appSettings ADD COLUMN displayPreferencesUpdatedAtEpochMs INTEGER` (nullable);
- `ALTER TABLE appSettings ADD COLUMN displayPreferencesDirty INTEGER AS kotlin.Boolean NOT NULL DEFAULT 0`, both last in the declaration;
- the backfill `UPDATE` that sets stamp `0` and dirty `1` where a group member differs from its first-run default;
- `CREATE TABLE mutedGenre (mediaType TEXT NOT NULL, genreId INTEGER NOT NULL, name TEXT NOT NULL, deleted INTEGER AS kotlin.Boolean NOT NULL DEFAULT 0, isDirty INTEGER AS kotlin.Boolean NOT NULL DEFAULT 0, updatedAtEpochMs INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (mediaType, genreId))`.

The schema fixture is `25.db`, generated with `generateCommonMainMuvissDatabaseSchema`.

**Not claimed by this ADR:** the develop branch already has `21.sqm` (#394's motion default) and `databases/22.db`, so the open stack's 21-to-24 order collides with develop. The stack-repair work owns the schema line, and this ADR does not add any `.sqm`.

## Implementation plan

Four issues, each a slice that one agent can build end to end:

- **#642 Display Preferences export as a group.** Writes `25.sqm`, the setter stamps, the restore rule, the `AppSettings.sq` comments, `ExportShapeTest`. Nothing is pushed or pulled. Done when the migration, restore and fresh-install tests pass and `backups/v1-export.json` still parses.
- **#643 Display Preferences sync.** Needs #642. `SyncTable.DISPLAY_PREFERENCES`, the one-element `SyncChangeSet.displayPreferences`, the Supabase table, `FakeSupabaseServer`, the pull that writes group columns only, and `docs/SYNC.md`. Tests: two devices, a fresh device never pushes, a pull onto a fresh device writes no device switch, and an unsent local edit newer than the pulled stamp survives.
- **#644 Muted Genres sync (#385).** Needs #642's table. The decision repository, the one-time conversion, `SyncChangeSet.mutedGenres`, `SyncTable.MUTED_GENRE`, the Supabase table, `FakeSupabaseServer`, and the export list. The regression test is the one above: A unmutes Talk, a fresh B mutes Horror, and A keeps Talk unmuted after both sync.
- **#645 Delete all data on a signed-in device.** A bug in code the slices do not touch. The owner decides the behaviour.

**Verification each slice carries:** `verifyMigrations` for the `25.sqm` chain; a conversion test from each starting state (never chose, `''`, a custom list); `SyncChangeSetShapeTest` with its expected names updated; `ExportShapeTest`; `backups/v1-export.json` still parsing; and the fresh-device tests. The sync stack's pgTAP and live `scripts/sync/verify-*.sh` checks have still not been run against the owner's project (#88), and the new tables inherit that gap.

**Landing and Play listing.** Nothing here is a user-visible feature that the store listing describes. Slice #642 changes restore behaviour, so its PR should say whether it needs `no-landing`.

**Feature flags.** Unchanged, as in the consequences above.

## Calls the owner still has to confirm

1. **Motion, tick and triage choices now sync** (reversing the comments in `AppSettings.sq` and #391's "not synced").
2. **No separate "ever chosen" marker.** The decisions carry it. This departs from the literal wording of the request, for the reason above.
3. **Co-watch's two switches stay per device** (`coWatchPoolListId`, `coWatchIncludeSeen`).
4. **Snooze period and placement sync** with the deck. The `AppSettings.sq` comment for them (`12.sqm`) says per device.
5. **#645's behaviour** for Delete all data on a signed-in device.
