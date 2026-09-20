# Sync is gated twice: a build switch, and a paid entitlement

Renumbered 0012 -> 0018 on 2026-09-04 (issue #26). This ADR and
`0012-what-counts-as-a-rewatch.md` were written on branches developed in
parallel and both merged claiming 0012, so every "ADR 0012" reference on `main`
was ambiguous. This one moved because it is referenced in roughly a third as
many places. Commit messages and issues written before that date which say
"ADR 0012" and mean the sync gate mean this file.

ADR 0009 built the sync seam and a Supabase backend behind it, and shipped
both without ever pointing them at a live project. Turning that on raised two
questions its "sync stays entirely optional" paragraph did not answer: what
stops it reaching production before it has been verified, and what makes it a
paid feature rather than a free one.

Three decisions, plus the fixes that had to land first for any of it to be
worth gating.

## The build gate is an explicit switch, not a missing key

ADR 0009's gate was key-absence: no `SUPABASE_URL`/`SUPABASE_ANON_KEY` →
`SyncAvailability.isConfigured()` is false → `NoOpSyncBackend`, and the
profile screen hides the section. That still holds, but it is now ANDed with a
new `SYNC_ENABLED` constant on `MuvissBuildConfig`, read from
`local.properties`/env exactly like the keys (ADR 0007).

Key-absence alone is a gate made of an *omission*. Nothing in the build
distinguishes "we chose not to ship this" from "someone forgot a property",
and anyone adding keys to debug a build would silently switch on a paid
feature. `SYNC_ENABLED` is the statement of intent, it is the one switch that
also hides the paywall, and a reviewer can see it is false.

Rejected: gating on Android's `debug` build type. It would guarantee no
release APK could contain sync, but it also makes the feature impossible to
dogfood on a release-signed build, and desktop/iOS/web have no equivalent.

## The entitlement gate lives inside `SyncEngine`

Sync is a paid feature, so `SyncEngine.syncNow()` consults an
`EntitlementGate` — a bare `fun interface` returning `suspend () -> Boolean`,
declared in `:core:sync` — and returns a new `SyncOutcome.NotEntitled` when it
refuses.

The location is the decision. The obvious place is the profile screen, which
owns the sync UI, but it is not the only caller: `MuvissApp`'s
`AutoSyncOnForeground` calls `SyncEngine` directly on every `ON_START`, so a
UI-level check would leave sync working for free on every launch. The engine
is the one place every path goes through.

Rejected: branching in `di/SyncModule.kt` the way the availability gate does.
A Koin `single` resolves once at graph build, and an entitlement changes
underneath the app — a purchase completes, a subscription lapses, a refund
lands. A user who has just paid would have to restart.

`:core:sync` does not depend on `:core:billing`, and never learns the word
"RevenueCat" — the same seam reasoning ADR 0009 applied to backends, and what
CONTEXT.md's `SyncEngine` entry means by "_Avoid_: backend, cloud". The two
meet in one file in the app shell (`di/BillingSyncBridge.kt`), bound after
`syncModule` so it overrides that module's permissive default. Making a
different feature the paid one is an edit to that file alone.

## `Unavailable` and `Locked` are deliberately opposite

`SyncAccountState` gains `Locked(email)` beside the existing `Unavailable`,
and the profile screen treats them as opposites: an unavailable build renders
nothing at all, a locked one renders the row and opens a paywall.

That is not an inconsistency. Offering a purchase for something the binary
cannot perform is a dead end; hiding a feature that could be bought makes it
undiscoverable. `Locked` carries the signed-in email so a lapsed subscriber
can still reach sign-out rather than being trapped behind the paywall.

## Fail closed when no store is configured

`:core:billing` is a new core module (infra, like `:core:sync` — no screen of
its own, so not a vertical slice per ADR 0004). It exposes
`EntitlementProvider` as a `Flow<Entitlement>`, with `Entitlement` being
`Active`/`Inactive`/`Unknown` rather than a `Boolean`: a store SDK cannot
answer before it reaches the network, and the honest answer meanwhile is
neither yes nor no. `isEntitled` resolves `Unknown` as *not* a grant.

With no store configured — every build today — `NoEntitlementProvider` is
bound and reports `Inactive`. Setting `SYNC_ENTITLEMENT_OVERRIDE=true` in
`local.properties` binds `AlwaysEntitledProvider` instead. It is a build
input, never a runtime one, so only whoever compiled the app can flip it.

Failing closed rather than open is the whole point: a build that ships with
`SYNC_ENABLED=true` but no billing wired up should produce a visibly dead
feature, not a free one. `NoEntitlementProvider` reports `Inactive` rather
than `Unknown` for a smaller reason — `Unknown` would leave the paywall
spinning forever on an answer that is never coming.

No RevenueCat dependency lands in this pass. `purchases-kmp` publishes Android
and iOS artifacts only, so a `commonMain` dependency breaks
`compileKotlinWasmJs` for every downstream module (the same failure CLAUDE.md
documents for `:models`' serialization scoping) — the vendor belongs in
`androidMain`/`iosMain`. The paywall follows the same shape: an `expect fun
rememberPaywallPresenter()` in `feature/profile/ui` with six actuals, mirroring
`feature/settings/ui`'s `DataExporter`, all of them the hand-rolled sheet for
now. Wiring `purchases-kmp-ui` in later is an edit to two platform files.

That is deferred because there is no Play Console app to sell through yet, and
the first upload waits on Google's review — so making working sync depend on
working billing would have blocked it for days.

## What had to be fixed first

Gating a broken feature is not worth doing. Three defects made sync unsafe to
turn on at all, all of them invisible to `SyncEngine`'s existing tests because
they lived either below the `SyncBackend` seam or in the engine's own
interaction with the clock:

- **A rejected push reported success.** `SupabasePostgrestClient.upsert`
  returns `Unit` and never checked `response.status`, and `:core:network`'s
  shared client leaves Ktor's `expectSuccess` off. A 401, 403 or 5xx therefore
  looked exactly like a 200: `runSync` proceeded to `clearDirty`, marking rows
  clean that the server never accepted, and nothing ever retried them. The
  user saw "Synced just now" and a fresh install restored an incomplete
  library. Status is now checked explicitly rather than by flipping
  `expectSuccess` globally, because TMDB shares that client and handles its own
  errors.

- **Tokens were never refreshed.** `SyncSession.expiresAtEpochMs` was
  computed, persisted and never read; no `grant_type=refresh_token` call
  existed. Supabase access tokens last about an hour, so sync died within the
  hour of signing in and only a manual re-login recovered it. There is now a
  proactive refresh (a minute before expiry) *and* a refresh-and-retry on 401,
  because the expiry stamp cannot predict a wrong device clock or a token
  revoked early. A failed refresh clears the session rather than looping:
  GoTrue single-uses refresh tokens, so once one is rejected every later
  attempt fails identically, and dropping to signed-out at least puts the
  sign-in button back.

- **The pull cursor came off the wrong clock.** `lastSyncedAtEpochMs` was
  stamped `clock.nowEpochMs()`, but rows carry the *writing* device's
  `updatedAtEpochMs`. A device running even slightly ahead stamps rows above
  the reading device's "now", and the next `gt.<cursor>` filter skips them
  permanently. The cursor now advances to the highest `updatedAtEpochMs` the
  server actually returned. That forced splitting one column into two
  (`7.sqm`): the cursor must be server-stamped and monotonic, while "last
  synced" answers "when did this device last run" and must advance even on an
  empty pull. Sharing them meant one of the two was always wrong.

`syncNow` also takes a `Mutex` now. The foreground hook and the profile
screen's button could previously overlap, and two interleaved cycles can mark
a row clean for a push that predates the user's edit.

## Consequences

- `:core:sync:jvmTest` and `:core:database:jvmTest` were **not** in
  `ci.yml`'s test allowlist — every sync and migration test written since EPIC
  9 had only ever run on a developer's machine. Both are in it now, along with
  `:core:billing` and the three feature `:data` modules this touched.
- `SupabaseSyncBackend` has tests for the first time (over a Ktor
  `MockEngine`). They cover status handling and the refresh paths; they cannot
  cover whether Supabase's real responses match the DTOs, which stays a manual
  step in `docs/SYNC.md`.
- *(Fixed by ADR 0020: a pull is now applied one transaction per page.)*
  `applyRemote` still writes row-by-row with no `database.transaction { }`, so
  a mid-pull failure leaves a half-merged database. Knowingly left: the cursor
  is not advanced on failure, so the next run re-pulls and repairs it. That is
  self-healing by luck rather than by design, and worth fixing if pulls ever
  get large enough to fail partway routinely.
- Sync tokens are still plaintext in SQLite (ADR 0009's consequence). A paid
  feature raises the stakes slightly, but not the trust boundary — the
  database is still on-device and unshared.
- `docs/PRIVACY.md` and `docs/store/DATA_SAFETY.md` still describe a
  local-only app, and both are correct while `SYNC_ENABLED` is unset. They
  must be updated before any build that sets it is published.
