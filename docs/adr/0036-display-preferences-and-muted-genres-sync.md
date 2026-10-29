# Display Preferences and Muted Genres sync across devices

`appSettings` is one row of 26 columns, and its comments in `AppSettings.sq` say most of them are per device: motion, triage and snooze choices, notifications, sync, crash reports, backup and the one-time introductions. #512 then exported ten of them with the backup, plus the muted-genre list as one string, restoring each value only while it still held its first-run default. Nothing reaches another device, so a new phone starts from defaults, and a restore cannot tell a value the person chose on purpose from one that was never changed.

Muted Genres (ADR 0031) is a set stored as one `;`-joined string. Synced as one column, last-write-wins on the whole string would lose one device's change to the other's, which is why ADR 0031 and #385 wanted one row per genre. Its defaults also have to stay out of the sync: a fresh device that pushed "Talk, News and Reality are muted" would re-mute titles another device had deliberately unmuted.

This records what crosses the device boundary, how each of the two groups replicates, and the plan. Where the owner's brief and a design constraint disagree, the ADR says so and the choice is listed under "Calls the owner still has to confirm".

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
| `animationsEnabled` | **Display** (was device) | The person's motion choice. The OS reduce-motion default still applies once per install (`motionDefaultApplied`, #394), and that write is not a change (see "Defaults are never data"). |
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
| `mutedGenres` | **Moved** to its own tables | A set, not a value (below). |

Ten columns are Display Preferences (`theme`, `language`, `region`, `animationsEnabled`, `triageDeckAnimations`, `tickAnimations`, `triageControlScheme`, `triageSnoozePeriod`, `triageSnoozePlacement`, `mutedGenresInSearch`). Fourteen are Device Switches. One moves out, and `id` is the key. That is all 26.

The export follows the same split: the Display Preferences and the Muted Genres are exported, and Device Switches never are. A restore never writes a Device Switch.

### The group is one row with one stamp

The Display Preferences replicate as **one row per account**, with one `updatedAtEpochMs` stamp, set by any member's change. The row wins on last-write-wins against another device's stamp, under the same rules as every table: a clean local copy takes the server's, and unsent edits are kept unless the pulled stamp is newer.

Per-field last-write-wins was rejected. It would need ten stamps and ten rows for settings people change together. Two costs remain, and the owner should accept them knowingly:

1. **An unsent edit to one member can be lost to another device's group.** If device A has an unsent change to `language` and device B pushes a newer `theme` first, A's push is discarded as older, and its `language` edit is replaced by B's group. Only offline edits reach this, because a clean device always takes the server copy.
2. **A new device's first change must not carry its defaults over the account.** Without a rule, the group is one row, so one change would push nine defaults with a fresh stamp over the account's real choices. The rule is in the next subsection.

**Pull before the first push, and a merge that lets defaults yield.** `SyncEngine` pushes dirty rows before it pulls (`pushDirty`, then `backend.pull`). For `DISPLAY_PREFERENCES` and `MUTED_GENRE_CHOICE` that order is wrong for a device that has never pulled them: it would push its defaults before it has seen the account's copy. So a device pulls those two tables before its first push of them (the device has no server baseline for the table yet, which the engine can see from the table's cursor). When that pull brings a group newer than the device's last baseline while the local group is dirty, the merge runs: a member still at its first-run default **yields** to the pulled value, and a member the device has changed keeps its local value. The merged group keeps the local stamp, so it still wins against older server rows. The test for "changed" is "not at its default", because the device does not store which members the person touched. The one edge is stated: a person who deliberately returns a member to its default on this device yields to the account's value. That is accepted.

### Defaults are never data

A device's first-run values are never written, never pushed and never dirty. Only a change the person makes stamps the group and sets its dirty flag.

- **The first-run values are the ones a fresh install holds.** `ensureRow` seeds `theme = 'SYSTEM'`, `language = ''`, `region = 'US'`, and the rest come from their column defaults: `animationsEnabled`, `triageDeckAnimations` and `tickAnimations` are `1`, `triageControlScheme = 'FOUR_WAY'`, `triageSnoozePeriod = 'ONE_WEEK'`, `triageSnoozePlacement = 'MIXED_IN'`, `mutedGenresInSearch = 0`. The column default `language = 'en-US'` is never used by an insert, because `ensureRow` names its columns and seeds `''`.
- **The OS motion default is not a change.** `ApplySystemMotionDefault` calls `flags.setAnimationsEnabled(false)` today, and that setter is the one a person's toggle uses, so it would stamp and dirty the group. #642 gives the OS default its own stamp-free write (an `UPDATE` that sets only `animationsEnabled`), used by that one caller and nothing else.
- **An upgraded install's motion value is always a choice or the default.** `21.sqm` marked `motionDefaultApplied` on every existing row without applying the OS default, so no existing row can hold an OS-derived value. For them, `animationsEnabled <> 1` is a choice.
- **Owner adoption does not dirty the group.** The first sync from a database with no owner "marks every local row dirty" (`docs/SYNC.md`). The group is dirty on adoption only when its stamp is non-null, so a device that never changed a display preference pushes nothing. The muted choice and decisions exist only once the person has chosen, so they are dirty on adoption as any row is.
- **The backfill.** `26.sqm` sets stamp `0` and dirty `1` where any member differs from the first-run value above, with `theme <> 'SYSTEM' OR language <> '' OR region <> 'US' OR animationsEnabled <> 1 OR triageDeckAnimations <> 1 OR tickAnimations <> 1 OR triageControlScheme <> 'FOUR_WAY' OR triageSnoozePeriod <> 'ONE_WEEK' OR triageSnoozePlacement <> 'MIXED_IN' OR mutedGenresInSearch <> 0`. The stamp is a constant, not the migration's clock, so the same database always produces the same rows (ADR 0013's reproducibility argument). Any real change made later outranks it.

### Muted Genres: a choice, and decisions over its defaults

This amends ADR 0031's note on #385. That note said a device's first explicit change writes the three defaults as real rows, live or tombstoned. That is withdrawn, for the reason below, and replaced by a choice marker plus decisions.

**The marker.** `mutedGenreChoice` is a singleton synced row per account. Its presence means "the person has chosen", as the owner asked. It stores `defaultsAtChoice`, the default set in force when the choice was made, in `MutedGenresCodec`'s format. It is written by the first explicit change, if no marker exists yet.

**The decisions.** A row in `mutedGenre` is a decision on one genre, keyed `(mediaType, genreId)`:

- a live row: the person muted this genre, whatever its default;
- a tombstone: the person unmuted it, whatever its default.

**The effective set.** A device with no marker uses the code's defaults and has no decisions. A device with a marker computes:

    muted(g) = row(g)?.let { !it.deleted } ?: (g in marker.defaultsAtChoice)

So "mute nothing" is the marker plus tombstones for the defaults. A device that never chose has no rows and mutes the defaults.

**Why not the full set on the first change.** Suppose the phone unmutes Talk at time t1, and a new tablet later mutes Horror at t2 > t1. If the tablet wrote the three defaults as its first change, it would push a live Talk row at t2 and re-mute Talk on the phone, against a choice the person made on the phone. Under decisions the tablet pushes the marker and the Horror row, and nothing about Talk.

**Why a marker with a snapshot, and not a marker alone.** A marker that said only "chosen" would make absent rows mean "not muted", and then the first change would have to write the full set, which is the over-reach above. The snapshot lets absent rows mean "as the defaults stood when chosen". It is also what makes the marker worth having: if a later release changes the defaults, a device that chose keeps the set it chose, and only a device that never chose takes the new defaults. Without the snapshot, a person who chose "mute nothing" would find the new default muted, which is exactly the failure the marker exists to prevent.

**Which devices still follow future defaults.** A device whose legacy string equals the default when converted gets no marker, because the string cannot tell "never chose" from "chose the defaults". It keeps following the code's defaults. That is accepted, and the conversion test pins it.

**The read rule.** Every query for the explicitly-muted set filters `AND deleted = 0`. A query that forgets it reads unmute decisions as mutes. This is the same lesson as `episodePlay` and `triageSnooze` (CLAUDE.md). The marker is the one singleton here; it has no `deleted` column.

**Conversion.** `26.sqm` creates the tables. The legacy `appSettings.mutedGenres` string is converted in Kotlin on the first open where `mutedGenreChoice` is absent and the string is not the default: a marker with `defaultsAtChoice` set to the code's defaults, a live row for each listed genre that is not a default, and a tombstone for each default the string leaves out. Everything written there has `isDirty = 1`, and decisions have `updatedAtEpochMs = 0`. Parsing stays in `MutedGenresCodec`, because genre names are free text and a SQL split would mishandle them. The string column stays, dead, as `syncCursorEpochMs` does. Export does not read it: the export derives it from the effective set, so it cannot go stale.

### Wire shape and migrations

- `display_preferences`: one row per account, primary key `user_id`, the group's columns, `updated_at_epoch_ms`, `server_seq`. It has no `deleted` column: the group is never removed, and a person resets a value by changing it.
- `muted_genre_choice`: one row per account, primary key `user_id`, `defaults_at_choice`, `updated_at_epoch_ms`, `server_seq`. No `deleted` column, for the same reason.
- `muted_genre`: primary key `(user_id, media_type, genre_id)`, `name`, `deleted`, `updated_at_epoch_ms`, `server_seq`.
- All three are single-owner tables with "own rows only" RLS, the `discard_stale_write()` trigger and a `(user_id, server_seq)` index, the same as `triage_snooze`.
- The Supabase migration timestamps must sort after `20261027000000`, which is taken by #588 (Taste Profile). Claim them when each branch opens.

### Export and restore

- `SettingsExport` keeps its ten fields and gains `displayPreferencesUpdatedAtEpochMs: Long? = null`.
- `MuvissDataExport` gains `mutedGenreChoice: MutedGenreChoiceExport? = null` (the snapshot and its stamp) and `mutedGenres: List<MutedGenreExport> = emptyList()`, tombstones included.
- `SettingsExport.mutedGenres` (the string) is still written, derived from the effective set at export time, so an older build can read a new backup.
- **`formatVersion` stays 2.** Every addition is defaulted, which is the rule since v1. Bumping it would make the v2 builds refuse a new backup with `NewerBackupVersion`, the wrong failure for a restore.
- Restore of the group takes the backup's group when its stamp is newer than the local one. A null local stamp loses to any backup stamp, and ties keep local. The restored group is dirty with the **backup's** stamp, not the current time, so it reaches the account and still loses to anything newer there.
- Restore of the choice takes the backup's marker when the device has none, and otherwise keeps the newer stamp.
- Restore of decisions merges per row on `updatedAtEpochMs`, ties keep local, and restored rows are dirty.
- A v2 file has none of the new fields. It restores as it does today: the group field by field over defaults, and its string through the same conversion.

## Considered and rejected

- **Per-field last-write-wins for the group.** Precise, but ten stamps and ten rows for settings that move together. It would also remove the offline-edit cost above, which the owner accepts.
- **The ADR 0031 full set on the first change.** It re-mutes a genre on another device that the person unmuted there, as shown above.
- **A marker without a snapshot.** It forces the full-set write, which is the same over-reach.
- **A muted list as one synced string.** Last-write-wins on the whole set loses one device's change to the other's.
- **Bumping `formatVersion`.** It would refuse new backups in old builds.
- **Keeping device-only motion switches.** The OS default already reaches each install, and a person who turned motion off on one device expects it on the others. A pulled value is the person's choice, not a device fact.
- **Skipping the pull before the first push.** The engine pushes first, so without it a fresh device's defaults would reach the account before the merge could see them.

## Consequences

- `AppSettings.sq`'s comments for the motion, triage and snooze columns no longer describe them as per-device. They are rewritten in #642, with the verdicts above.
- The pinned lists change deliberately: `SyncChangeSetShapeTest` (three new elements), `ExportShapeTest` (the new fields), and `FakeSupabaseServer` (three schemas).
- **The engine gains one table-specific order.** The two singleton tables pull before their first push. That is the only ordering exception in the cycle, and #643 makes it explicit and tests it.
- **No new `FeatureFlags` member.** Sync's existing gates apply, so no fake changes. If one were ever added it would have to be implemented by `AppSettingsFeatureFlags`, `core/testing`'s `FakeFeatureFlags`, and four test-local fakes: `SettingsViewModelTest`, `TriageUiTestFixtures`, the profile `SyncRepositoryFixtures`, and `CrashReportsSettingTest`'s `NoFlags`.
- **Delete all data does not yet stay deleted on a signed-in device.** Its erase does not sign out, so the next sync adopts the account and pulls from cursor zero. This was already true for the library, and it now extends to these settings. Recorded in #645; it is not fixed here.
- ADR 0031's note on #385 is superseded; the rest of ADR 0031 stands.
- Tick animations (#391) now sync; the decision above reverses its "per device, not synced" for the motion switches.

## Schema

**`26.sqm`** (the number is the coordinator's fixed order: 553 takes 22, 543 takes 23, 588 takes 24, 563 takes 25, this takes 26). It is written in full by the first slice that merges, which is #642, and the later slices do not change it. It contains:

- `ALTER TABLE appSettings ADD COLUMN displayPreferencesUpdatedAtEpochMs INTEGER` (nullable);
- `ALTER TABLE appSettings ADD COLUMN displayPreferencesDirty INTEGER AS kotlin.Boolean NOT NULL DEFAULT 0`, both last in the declaration;
- the backfill `UPDATE` described under "Defaults are never data";
- `CREATE TABLE mutedGenre (mediaType TEXT NOT NULL, genreId INTEGER NOT NULL, name TEXT NOT NULL, deleted INTEGER AS kotlin.Boolean NOT NULL DEFAULT 0, isDirty INTEGER AS kotlin.Boolean NOT NULL DEFAULT 1, updatedAtEpochMs INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (mediaType, genreId))`, with `CREATE INDEX mutedGenre_dirty ON mutedGenre(isDirty) WHERE isDirty = 1;` as 15.sqm does for the other synced tables;
- `CREATE TABLE mutedGenreChoice (id INTEGER NOT NULL PRIMARY KEY, defaultsAtChoice TEXT NOT NULL, updatedAtEpochMs INTEGER NOT NULL DEFAULT 0, isDirty INTEGER AS kotlin.Boolean NOT NULL DEFAULT 1)`. It is a singleton like `appSettings`, so it has no dirty index.

The schema fixture is `27.db` (migrating `26.sqm` takes a v26 database to v27, as `21.sqm` took v21 to v22 and produced `22.db`), generated with `generateCommonMainMuvissDatabaseSchema`.

**Not claimed by this ADR:** the develop branch already has `21.sqm` (#394's motion default) and `databases/22.db`, so the open stack was renumbered by the coordinator to 22 (#553), 23 (#543), 24 (#588) and 25 (#563), which leaves develop's 21 alone. This ADR claims 26, and does not add any `.sqm`.

## Implementation plan

Four issues, each a slice that one agent can build end to end:

- **#642 Display Preferences export as a group.** Writes `26.sqm` (all of it, as above), the setter stamps, the stamp-free OS motion write, the restore rules for the group, the choice and the decisions, the `AppSettings.sq` comments, and `ExportShapeTest`. Nothing is pushed or pulled. Done when the migration, the backfill, restore and fresh-install tests pass and `backups/v1-export.json` still parses.
- **#643 Display Preferences sync.** Needs #642. `SyncTable.DISPLAY_PREFERENCES`, the one-element `SyncChangeSet.displayPreferences`, the Supabase table, `FakeSupabaseServer`, the pull-before-first-push rule, the merge in which first-run defaults yield, the pull that writes group columns only, and `docs/SYNC.md`. Tests: two devices; a fresh device never pushes; a first change on a new device does not carry defaults over the account's values; a pull onto a fresh device writes no device switch; and an unsent local edit newer than the pulled stamp survives.
- **#644 Muted Genres sync (#385).** Needs #642's tables. The decision repository and the effective-set rule, the marker written on the first change, the one-time conversion, `SyncChangeSet.mutedGenres` and `.mutedGenreChoices`, `SyncTable.MUTED_GENRE` and `MUTED_GENRE_CHOICE`, the Supabase tables, `FakeSupabaseServer`, and the export. The regression test is the one above: A unmutes Talk, a fresh B mutes Horror, and A keeps Talk unmuted after both sync. A second: a later change to the code's defaults does not reach a device that chose.
- **#645 Delete all data on a signed-in device.** A bug in code the slices do not touch. The owner decides the behaviour.

**Verification each slice carries:** `verifyMigrations` for the `26.sqm` chain; a conversion test from each starting state (never chose, `''`, a custom list, the defaults exactly); `SyncChangeSetShapeTest` with its expected names updated; `ExportShapeTest`; `backups/v1-export.json` still parsing; and the fresh-device tests. The sync stack's pgTAP and live `scripts/sync/verify-*.sh` checks have still not been run against the owner's project (#88), and the new tables inherit that gap.

**Landing and Play listing.** Nothing here is a user-visible feature that the store listing describes. Slice #642 changes restore behaviour, so its PR should say whether it needs `no-landing`.

## Calls the owner still has to confirm

1. **Motion, tick, triage and snooze choices now sync**, reversing the comments in `AppSettings.sq` and #391's "not synced".
2. **The group's two costs** (above): an unsent edit to one member can be lost to another device's group, and a person who returns a member to its default on one device yields to the account's value. Per-field stamps would remove both, at ten stamps.
3. **Co-watch's two switches stay per device** (`coWatchPoolListId`, `coWatchIncludeSeen`).
4. **Snooze period and placement sync** with the deck. The `AppSettings.sq` comment for them (`12.sqm`) says per device.
5. **A device whose legacy string equals the defaults never chooses**, so it keeps following future defaults. Accepted, as above.
6. **#645's behaviour** for Delete all data on a signed-in device.
