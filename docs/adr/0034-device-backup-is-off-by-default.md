# Device Backup is off by default, and the library is excluded unless the person asks

Muviss's library (the SQLite database, watch history included) is the one thing the person cannot rebuild by reinstalling. Each phone already has a backup of its own — iCloud or a device backup on iOS, Google's backup and device-to-device transfer on Android — and an app's files are in it unless the app says otherwise. So the question is not whether a library reaches a new phone, but who decides.

## Decision

- **Device Backup** (CONTEXT.md) is a per-device switch, `appSettings.deviceBackupEnabled`, **off by default**. It is the only thing that lets the OS carry the library. Off, the library is left out of the OS backup; on, it is included. Nothing is copied by Muviss either way: the copy is held by Apple or Google under the person's own account, and Muviss has no server to receive it.
- **Off is the platform norm's opposite.** Most apps simply get backed up. We chose the opposite because a copy of someone's viewing history sitting in a backup they never chose to make is the failure this app exists to avoid, and the switch is asked for in the first-run introduction, where it costs one tap to say yes.
- **The switch is per device, not synced and not in the data export.** It describes this device's backup, not the library, so it is neither a `SyncChangeSet` field nor an `ExportShapeTest` field. `SyncChangeSetShapeTest` and `ExportShapeTest` are unchanged and green.
- **KSafe and the Own TMDB Key are never backed up**, on either platform, whatever the switch says. They are secrets bound to a device key: a copy restored onto another phone cannot decrypt them, and a copy that could would be a leak.
- **The switch is offered only where the OS has a backup Muviss can honour** (`platformCanBackUp`: Android and iOS). Desktop and the web have none, so the row and the onboarding page are absent there.
- **Delete all data resets the switch to off and removes the OS exclusion** with it. It does not reach into a backup the OS already holds; that is outside the app, and the privacy policy says so.

## iOS

`DeviceBackupExclusion` sets `NSURLIsExcludedFromBackupKey` on the database's **directory** (a `Database` subdirectory of the App Group container, or of Documents without the entitlement, ADR 0016; its own directory so the flag covers nothing else the app or widget stores, and older installs are moved into it once on first open) and on `muviss.db`, `-wal` and `-shm` where they exist. The directory flag is the one that holds: SQLite deletes and recreates the sidecars while the app runs, and a new file carries no per-file flag of its own. Excluding a directory keeps its contents out of the backup, so a recreated sidecar is covered. Excluding the main file but not its `-wal` (or the reverse) is the failure to avoid, because a backup holding `muviss.db` without its log restores a library missing its most recent commits.

The flag is applied on every open, from the stored choice (`DatabaseDriverFactory.create`), and on every toggle and on Delete all data. It is **not** reapplied on each write, so a sidecar created by a write that has no open between it and the backup relies on the directory flag alone. That is the design, and the device checklist in the PR verifies it.

**Existing iOS installs.** Before this change no exclusion was set on iOS, so any install that already exists was backed up by iCloud or a device backup. No build with a library has reached the App Store or TestFlight yet (ADR 0030's internal stage is the first), so no real user is affected. The migration default of 0 applies to such dev installs too: their next open excludes the library from future backups.

## Android

The backup agent (`MuvissBackupAgent`, `app/androidApp`) is an **allowlist**. It offers the library database, with its `-wal`, when the stored switch is on, and offers **nothing else**: KSafe's DataStore, shared preferences and every other file are never handed to the framework. The agent runs in restricted mode, so it reads the switch straight from the database file (`SELECT deviceBackupEnabled FROM appSettings WHERE id = 0`, read-only) and never touches Koin. `DeviceBackupDecider` is the pure rule (only an explicit on includes the library; an unreadable or missing value is off), and it is what the JVM test pins.

`dataExtractionRules` states `<cloud-backup>` and `<device-transfer>` explicitly, excluding the same domains (`root`, `file`, `sharedpref`, `external`, `device_root`). Stating both sections matters: a missing section means that transfer path is **fully enabled** except for `no-backup` and `cache`, per the framework's rules. `backup_rules.xml` is the same set for Android 11 and below.

**Audit: what transferred before.** The manifest had `allowBackup="false"` and no `dataExtractionRules`. Android's documentation says that on API 31 and higher, `allowBackup="false"` disables cloud backup on some manufacturers but **does not disable device-to-device transfer**. So before this change every file in the app's data directory, including KSafe's DataStore, was eligible for a device-to-device transfer. The secrets would not have decrypted on the new device, but the database would have arrived whether or not the person wanted it. This is the reason `dataExtractionRules` now exists at all.

**Not verified on a device.** The XML rules' interaction with a custom `onFullBackup` is not stated in the documentation. The agent is written so that the XML is a second line of defence, not the mechanism. Whether a backup taken with the switch **on** actually contains `muviss.db`, and one taken with it off contains nothing, is the device checklist in the PR, to be run with `adb shell bmgr backupnow` and a restore on a second device.

## Considered options

- **Include everything by default, with a switch to exclude.** Rejected: the default is the decision that people never revisit.
- **Keep `allowBackup="false"` and rely on the manufacturer's behaviour.** Rejected: the documentation says the behaviour varies, and the audit above shows it was not even stopping transfer.
- **Back up the library through Muviss's own export file, not the OS.** Already exists (Settings → Data → Export) and stays. It is not a substitute for a phone's backup, because it needs the person to remember to make it.
- **Ask Apple for an iCloud Drive document rather than a backup exclusion.** Rejected: it would put a live SQLite file into a user-visible document store, and it would be a second copy to keep consistent with the first.

## Consequences

- **Schema v20** (`19.sqm`): `appSettings.deviceBackupEnabled INTEGER NOT NULL DEFAULT 0`, appended last. The number is 19, not the 18 the branch was first given: Muted Genres merged `18.sqm` (schema v19) first, so this migration takes the next free number, with fixture `20.db` (`AGENTS.md`'s rule).
- **The Android manifest is no longer `allowBackup="false"`.** `docs/PRIVACY.md` and the manifest comment say so; both are updated here. The lint baseline's `allowBackup` deprecation finding is about this attribute.
- **The Own TMDB Key on iOS is not claimed as excluded.** On Android it is excluded by the allowlist and the XML rules. On iOS it sits in KSafe's Keychain-backed store, and whether an encrypted device backup carries Keychain items with its accessibility class is not verified here. Filed as a follow-up.
- **App Store nutrition label is unaffected**: an iCloud backup is Apple's, and Muviss collects nothing from it. The Play Data safety form is unaffected for the same reason.
- **A restore does not touch the switch.** It is a per-device choice, so a library restored from a backup arrives with whatever this device's switch says.
