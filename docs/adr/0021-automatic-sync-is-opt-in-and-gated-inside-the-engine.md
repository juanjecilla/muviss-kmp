# Automatic sync is opt-in, and every gate is enforced inside the engine

EPIC 40 (#86), on top of EPIC 39's correctness work (ADR 0020). Until this,
sync ran when a person pressed "Sync now" and, without anyone deciding it, on
every `ON_START` of the app. The owner asked for the other half: remote-to-local
and local-to-remote sync happening in the background, behind a feature flag and
only when the user turns it on.

Turning sync up from "sometimes" to "always" multiplies the effect of any
defect in it, which is why this waited for EPIC 39. It also multiplies the cost
of getting the *gate* wrong: an automatic trigger that fires when it should not
sends someone's library to a server without a tap. So most of this ADR is about
where the gates live.

## Three layers, all enforced in `SyncEngine.syncNow(trigger)`

1. **A build flag**, `SYNC_BACKGROUND_ENABLED`, read from `local.properties`/env
   exactly like `SYNC_ENABLED` (ADR 0007) and folded into
   `SyncAvailability.isBackgroundAvailable()` (which is `isConfigured() &&
   SYNC_BACKGROUND_ENABLED`). Release CI sets none of them, so the feature ships
   dark. When false the Profile screen renders no switch at all, the same rule
   ADR 0018 set for `Unavailable`.
2. **A per-device switch**, `FeatureFlags.syncAutomatically`, stored in
   `appSettings.syncAutomatically` (the column came with EPIC 39's `9.sqm`, so
   this epic has no migration of its own), default **off**, never synced. It is a
   statement about *this* device's battery and data plan, not about the library.
3. **A session and an entitlement**, unchanged from ADR 0018.

`syncNow(trigger)` takes a `SyncTrigger`: `Manual | Foreground | Change |
Periodic | Resume`. A pure `AutoSyncPolicy.decide(...)` over five inputs (trigger,
build flag, switch, session, entitlement) returns `Run`, `Disabled`,
`NotSignedIn` or `NotEntitled`. Manual skips the first two gates only. An
automatic trigger that fails the build flag or the switch returns
`SyncOutcome.Disabled` having touched neither the database nor the network, and
*the entitlement gate is not even asked* (it can be a store lookup).

The policy is enforced in the engine, not in whoever calls it, for the reason
ADR 0018 already gave: the profile screen is not the only caller. The existing
`AutoSyncOnForeground` in `MuvissApp` now asks `SyncCoordinator.onForeground()`
which asks the engine, and with the switch off (the default) it does nothing.
That hook was the one place sync ran without being asked. The engine also
re-reads the switch *after* waiting for its mutex, so an automatic run queued
behind a long manual one does not go ahead if the person turned the switch off in
between. `AutomaticSyncIntegrationTest` reads the *server's request log* rather
than a return value, so a gate held only by the UI would fail it.

Rejected: gating in the UI and the schedulers only (cheaper, and exactly the
mistake ADR 0018 exists to prevent); a fourth runtime layer for "battery/data
saver" (the OS already withholds WorkManager and BGTask work under those).

## The triggers, and what each cannot promise

| Platform | Mechanism |
|---|---|
| All | `SyncCoordinator`: watch the dirty-row count, **debounce 5 s**, **at most one change run per 30 s**, **exponential backoff capped at 15 min** on failure, only while the process is alive. A pull cannot feed it back: pulled rows are written clean, so the count never rises. Foreground skipped if a cycle finished under **60 s** ago (`ON_START` and `ON_RESUME` fire back to back). |
| Android | `SyncWorker`, WorkManager periodic **1 h**, unique work `KEEP`, `CONNECTED` and battery-not-low, resolved through Koin like `NewEpisodesWorker`. `SyncScheduleController` in `MuvissApplication` schedules it while the policy is on and cancels it when it goes off, **including at startup** (WorkManager persists jobs across process death, so a job scheduled last session would otherwise outlive the switch). Its `-keep` rule is in `proguard-rules.pro`: R8 strips a worker nothing references by type. |
| iOS | Piggybacked inside `IosBackgroundRefresh.handle`, before the episode refresh, reporting failure when the expiration handler fired. **No new `Info.plist` identifier.** The system decides when; the UI says "when the system allows". |
| Desktop | A 15 minute timer next to `DesktopEpisodeRefresh`, **only while the window is open**. The UI says so. |
| Web | `visibilitychange` to visible and `online` call `SyncCoordinator.onResume()`. Inert until web sign-in exists (#46, EPIC 32). |

The coordinator retries a failed change run *inside* its collector rather than
waiting for another write, so a burst that arrives during a backoff coalesces
into the retry. Outcomes that mean "nothing was attempted" (`Disabled`,
`NotSignedIn`, `NotEntitled`, `AccountChanged`) and a dead session are not
retried on a timer: only a new write, a sign-in or the switch is a reason to try
again. Turning the switch off cancels the watch, including a run in flight.

## Failures are typed, not strings

`SyncOutcome.Failed` carries a `SyncFailureReason` (`Offline`, `Unauthorised`,
`Server`, `Unknown`) plus diagnostic text that is never shown. Classification
walks the cause chain and matches transport errors by class *name*, because
`UnknownHostException`, a browser `TypeError: Failed to fetch` and
`DarwinHttpRequestException` are different classes on every target and common code
sees none of them. A rejected token refresh now throws `SyncSessionExpiredException`
(public, part of the `SyncBackend` contract) instead of surfacing as an HTTP 400
that reads like a server fault. Copy comes from `SyncCopy` in
`feature/profile/domain`, keyed by a mirror enum; it never reads `e.message`.
This mirrors EPIC 27's `MetadataError` idea without depending on it.

**`SyncFailureReason` and `MetadataError` stay two taxonomies, on purpose**
(issue #116). `MetadataError` (`:models`) models a TMDB HTTP call and carries
members sync has no use for (`RateLimited`, `NotFound` — TMDB's REST API and
PostgREST's are not the same failure surface, and a 404 means something
different on each). Folding `SyncFailureReason` into it would give `:core:sync`
a `:models`-level dependency for a handful of shared names (offline,
unauthorised, server, unknown) and force one enum to serve two backends that
will keep changing independently — the TMDB side already grew `RateLimited`
after this one shipped. A shared `NetworkFailure` core both map onto was
considered and rejected for the same reason plus the churn of touching every
call site of both: the actual overlap is four names and a `when` branch each,
not a real duplication problem. `SyncCopy` and `MetadataError.toUserMessage`
independently keeping to "never raw exception text" (CLAUDE.md's EPIC 27 rule)
is what actually needs to stay true, and both do.

The reason and the diagnostic are stored together in `syncState.lastError` as
`REASON: text`. The epic owns no schema number, so there is no column for the
reason; `fromStored` tolerates anything else (text written before this existed
reads as no reason). A background failure is therefore finally visible: the
Profile status line reads "Last sync failed: you seem to be offline" with Retry.

A session that dies signs the device out. To tell that from a person signing out,
`SyncAccountState.SessionExpired` is derived from *no session + last failure was
`Unauthorised`*, and an explicit sign-out clears the failure. The screen then
says "Session expired, sign in again" instead of stopping silently.

`AccountChanged` (EPIC 39) is surfaced clearly in the UI and nothing is offered
to fix it: discarding or merging is EPIC 32's ADR 0019.

## Sign-in still syncs once, with the switch off (issue #115)

`MuvissApp`'s `CompleteOAuthOnRedirect` calls `syncEngine.syncNow()` — a
`Manual` trigger — the moment an OAuth code exchange succeeds, regardless of
`syncAutomatically`. This is a deliberate exception to "the switch off means
nothing leaves the device by itself", decided rather than left as a leftover
gap: completing sign-in is itself the explicit action, the same category as
pressing "Sync now", not a background trigger acting on the person's behalf.
It is also not optional in practice — the first cycle after a sign-in is what
adopts the account (`syncState.ownerAccountId`) and uploads any pre-v7 plays,
so without it a freshly signed-in device sits showing "Never synced" and
Progress/Library keep showing only local data until someone finds the manual
button.

The alternative — `Foreground` instead of `Manual`, so the switch actually
gates it — was considered and rejected for exactly that reason: it would make
"sign in" and "see your library" two separate steps whenever the switch is
off, which is worse UX for a case (opt-in sync) already only a few users
reach at all. If this decision is ever revisited, change the trigger in
`CompleteOAuthOnRedirect` and pin it with a test in
`AutomaticSyncIntegrationTest` (or a `SyncFlowUiTest`-style Compose test, since
`AutomaticSyncIntegrationTest`'s `SyncApp` harness does not drive the OAuth
redirect composable itself) that signs in with the switch off and asserts on
the server's request log, the same way every other gate in this ADR is
verified.

## The weekly full reconcile

EPIC 39's cursor is a server-assigned sequence stamped at insert, not at commit,
so a transaction that commits late can leave a row behind a cursor another device
has already passed (ADR 0020 "Known limitations"). Once a week a cycle resets the
cursors and pulls from the beginning. The timestamp lives in `syncCursor` under
the reserved name `_lastFullPullAt`, because no schema number is free for a
column; `loadCursors` reads only the six table keys, so it never looks like a
cursor, and `deleteAll` clearing it is right because a reset is always followed
by a full pull. Any test that reads `syncCursor` directly must ignore names
starting with `_`. An install that has cursors but no stamp (every install that
synced before this) does one full pull, then follows the weekly cadence.

## What is not verified

No CI runs any of the platform triggers. The mappers and controllers are plain
JVM tests; a real WorkManager run, a real `BGAppRefreshTask`, a packaged desktop
app left open, two browser tabs, and a live Supabase project are manual steps
listed on #86.
