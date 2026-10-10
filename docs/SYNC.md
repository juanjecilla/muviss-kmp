# Sync & accounts (EPIC 9)

The optional cloud sync layer: `SyncEngine` (`:core:sync`) replays local
dirty rows to a backend and merges remote changes back in. See ADR 0009 for
the design rationale (multi-backend seam, plain Ktor over `supabase-kt`,
last-write-wins conflict resolution) — this document is the concrete
Supabase project setup and the SQL schema `SupabaseSyncBackend` talks to.

Sync is entirely **optional**, and gated twice (ADR 0018). **Automatic** sync is
gated three times (ADR 0021), see [Automatic sync](#automatic-sync-epic-40-adr-0021):

1. **Build gate** — `SYNC_ENABLED` *and* both Supabase keys must be present.
   Miss any of the three and `SyncAvailability` reports unconfigured,
   `di/SyncModule.kt` binds `NoOpSyncBackend`, and the profile screen hides
   the sync section outright. Nothing else about the app changes. Release CI
   sets none of them, which is what keeps sync out of production.
2. **Entitlement gate** — sync is a paid feature. `SyncEngine.syncNow()`
   consults an `EntitlementGate` and returns `NotEntitled` if the user has not
   paid, so no caller (the foreground hook included) can walk around it. With no
   store wired up (every build today) `:core:billing` binds
   `NoEntitlementProvider`, which reports Inactive — set
   `SYNC_ENTITLEMENT_OVERRIDE=true` in `local.properties` to grant it to your own
   build. Since EPIC 32 the **server** enforces it too, through a `sync_until`
   claim the token hook stamps from `public.entitlement` — so against a project
   with those migrations the override alone is not enough, see
   [Entitlement](#entitlement-the-servers-half-of-the-paid-gate-epic-32-adr-0019).

An unavailable build renders no sync UI at all; an unentitled one renders the
row and a paywall. Those are deliberately opposite, see ADR 0018.

## Setting up a Supabase project

1. Create a free project at [supabase.com](https://supabase.com).
2. **Enable anonymous sign-ins** (Authentication → Providers → Anonymous
   Sign-Ins) only if you intend to use `SyncBackend.signInAnonymously`. The
   shipped UI does not: an anonymous user is per-device, so two devices get two
   identities and nothing syncs between them (ADR 0014). It stays on the
   interface for a future entry point, and this step is optional today.
3. **Sign-in is OAuth (ADR 0014).** Muviss sends no email at all — the email
   one-time-code flow was removed because Supabase's free tier refuses to
   customise the email templates, and its stock ones send a clickable link
   where the app asked for a typable code. Two things to set up:

   **a. Register an OAuth app with the provider.** For GitHub: Settings →
   Developer settings → OAuth Apps → New OAuth App. The **Authorization
   callback URL** is Supabase's, not the app's:

   ```
   https://<your-project-ref>.supabase.co/auth/v1/callback
   ```

   **b. Enable it in Supabase**: Authentication → Providers → GitHub, on, and
   paste the client ID and secret. Adding Google later is the same two steps
   plus one entry in `SyncUiState.providers` — the client takes the provider
   as a query parameter, so there is no second code path.

   The redirect back *into the app* is `muviss://auth-callback` on Android and
   iOS, and it has to be identical in three places that cannot reference each
   other: `OAUTH_REDIRECT_URI` in `core/sync/.../OAuthRedirect.kt`, the
   `auth-callback` intent filter in the Android manifest (plus iOS's
   `CFBundleURLSchemes`), and `additional_redirect_urls` in
   `supabase/config.toml`. The last one is a security control — GoTrue refuses
   to redirect anywhere not on that allow-list — and is applied with
   `supabase config push`.

   **Desktop is the exception**: it owns no URL scheme that survives
   `./gradlew run`, so it binds a loopback HTTP server and hands GoTrue
   `http://127.0.0.1:<port>/auth-callback` instead (**ADR 0017**). Ports
   53682-53684 are tried in order, and all three must be on the allow-list too
   — `LoopbackRedirectServer.PORTS` in `:app:desktopApp` is the same list.

   Know when a missing allow-list entry actually bites: GoTrue's `/authorize`
   accepts **any** `redirect_to` and 302s to the provider regardless, so the
   flow starts normally. Enforcement happens when GoTrue redirects *back*,
   after the user has authorized — which is why a drift presents as a browser
   landing on `site_url` with "cannot connect to the server", not as an error
   up front.

   Sign-in works on **Android, iOS and desktop**. Web is the one target left:
   `Pkce.js.kt` / `Pkce.wasmJs.kt` are still `unsupportedOnThisTarget()`, and
   it needs an in-page redirect rather than either mechanism above.
4. Apply the schema — six synced tables, their Row Level Security policies,
   the last-write-wins trigger and the pull-cursor indexes:

   ```bash
   brew install supabase/tap/supabase   # once
   supabase login                       # opens a browser
   supabase link --project-ref <your-project-ref>
   supabase db push
   ```

   The schema lives in `supabase/migrations/` and **that file is the source of
   truth**, not the SQL quoted below. It used to be the other way round: the
   only copy was fenced in this document, so standing up a project was a
   copy-paste job that left no record of what had actually been applied and no
   way to tell two projects apart. The block below is kept for reading; if the
   two ever disagree, the migration is right.

   The CLI never sees `SUPABASE_ANON_KEY` — it authenticates with your own
   account token from `supabase login`, and `supabase link` stores only the
   project ref. Neither belongs in `local.properties`.
5. Copy the project's URL and anon (public) key (Project Settings → API)
   into `local.properties` (gitignored, never commit real keys):

   ```properties
   SYNC_ENABLED=true
   SYNC_ENTITLEMENT_OVERRIDE=true
   SYNC_BACKGROUND_ENABLED=true   # optional: ships the "Sync automatically" switch (EPIC 40)
   SUPABASE_URL=https://your-project.supabase.co
   SUPABASE_ANON_KEY=your-anon-key
   ```

   `SYNC_BACKGROUND_ENABLED` only means anything on top of `SYNC_ENABLED` and the
   keys. Leave it out and the Profile screen renders no switch and nothing ever
   syncs by itself; a manual "Sync now" is unaffected.

   Note that Supabase's free tier **pauses a project after 7 days of
   inactivity**. A paused project fails in a way that reads like an app bug;
   check the dashboard before debugging the client.

   Same generated-constant mechanism as `TMDB_API_KEY` and `SENTRY_DSN` (ADR
   0007) — read by `core/sync/build.gradle.kts` at build time, baked into
   `MuvissBuildConfig`, never committed. CI and any clone without these two
   properties builds and runs exactly as before, with sync hidden.

## Schema

> Reference copy of the **original** schema. The executable ones are
> `supabase/migrations/20260829000000_sync_schema.sql` and, since EPIC 39,
> `supabase/migrations/20260920000000_sync_server_seq.sql`, applied with
> `supabase db push` — see step 4 above. The second **replaces
> `discard_stale_write()` below** (it now also clamps `updated_at_epoch_ms` and
> stamps `server_seq`, and fires on insert as well as update), adds a
> `server_seq bigint not null` column to every table below, and indexes
> `(user_id, server_seq)`. The pull pages by that column, not by
> `updated_at_epoch_ms` — see "How a sync cycle works" and ADR 0020. Where this
> block and the migrations disagree, the migrations are right.

One table per synced local table (`collectionEntry`, `episodeProgress`,
`mediaList`, `listEntry`, `triageDecision`, `episodePlay` — see their `.sq`
files in `core/database`), each
keyed by `(user_id, <the local table's own natural key>)`. Column names are
`snake_case` versions of the local column names (PostgREST convention);
`SyncChangeSet`'s `@SerialName` annotations (`core/sync`) match these exactly.

```sql
-- Shared last-write-wins guard: silently discards an incoming write whose
-- updated_at_epoch_ms is older than what's already stored, by rewriting NEW
-- back to OLD rather than raising — the client's upsert still returns 200,
-- it just didn't move anything. This is what lets SyncEngine push
-- unconditionally (no read-before-write) and still converge correctly
-- regardless of which device's push physically arrives first — see ADR
-- 0009's "Engine order is push, then pull" section.
create or replace function discard_stale_write()
returns trigger as $$
begin
  if new.updated_at_epoch_ms < old.updated_at_epoch_ms then
    return old;
  end if;
  return new;
end;
$$ language plpgsql;

-- collection_entry --
create table collection_entry (
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  media_id text not null,
  media_type text not null,
  title text not null,
  poster_url text,
  release_year integer,
  production_status text not null,
  total_episodes integer not null default 0,
  aired_episodes integer not null default 0,
  favorite boolean not null default false,
  genres text not null default '',
  runtime_minutes integer,
  added_at_epoch_ms bigint not null,
  updated_at_epoch_ms bigint not null,
  deleted boolean not null default false,
  rating integer,
  note text,
  primary key (user_id, media_id)
);

alter table collection_entry enable row level security;
create policy "own rows only" on collection_entry
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create trigger collection_entry_lww
  before update on collection_entry
  for each row execute function discard_stale_write();

-- episode_progress -- (no `deleted`: ticks are idempotent booleans, see ADR 0009)
create table episode_progress (
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  episode_id text not null,
  media_id text not null,
  season_number integer not null,
  episode_number integer not null,
  seen boolean not null default false,
  updated_at_epoch_ms bigint not null,
  primary key (user_id, episode_id)
);

alter table episode_progress enable row level security;
create policy "own rows only" on episode_progress
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create trigger episode_progress_lww
  before update on episode_progress
  for each row execute function discard_stale_write();

-- media_list --
create table media_list (
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  id text not null,
  name text not null,
  created_at_epoch_ms bigint not null,
  updated_at_epoch_ms bigint not null,
  deleted boolean not null default false,
  primary key (user_id, id)
);

alter table media_list enable row level security;
create policy "own rows only" on media_list
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create trigger media_list_lww
  before update on media_list
  for each row execute function discard_stale_write();

-- list_entry --
create table list_entry (
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  list_id text not null,
  media_id text not null,
  added_at_epoch_ms bigint not null,
  updated_at_epoch_ms bigint not null,
  deleted boolean not null default false,
  primary key (user_id, list_id, media_id)
);

alter table list_entry enable row level security;
create policy "own rows only" on list_entry
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create trigger list_entry_lww
  before update on list_entry
  for each row execute function discard_stale_write();

-- triage_decision --
create table triage_decision (
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  media_id text not null,
  media_type text not null,
  verdict text not null,
  title text not null,
  poster_url text,
  decided_at_epoch_ms bigint not null,
  resolved boolean not null default true,
  updated_at_epoch_ms bigint not null,
  deleted boolean not null default false,
  primary key (user_id, media_id)
);

alter table triage_decision enable row level security;
create policy "own rows only" on triage_decision
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create trigger triage_decision_lww
  before update on triage_decision
  for each row execute function discard_stale_write();

-- triage_snooze -- (EPIC 42, ADR 0023)
create table triage_snooze (
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  media_id text not null,
  media_type text not null,
  title text not null,
  year bigint,
  poster_url text,
  overview text,
  snoozed_at_epoch_ms bigint not null,
  due_at_epoch_day bigint not null,
  updated_at_epoch_ms bigint not null,
  deleted boolean not null default false,
  primary key (user_id, media_id)
);

alter table triage_snooze enable row level security;
create policy "own rows only" on triage_snooze
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create trigger triage_snooze_lww
  before update on triage_snooze
  for each row execute function discard_stale_write();

-- episode_play -- (ADR 0013)
create table episode_play (
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  id text not null,
  episode_id text not null,
  media_id text not null,
  watched_at_epoch_ms bigint not null,
  updated_at_epoch_ms bigint not null,
  deleted boolean not null default false,
  primary key (user_id, id)
);

alter table episode_play enable row level security;
create policy "own rows only" on episode_play
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create trigger episode_play_lww
  before update on episode_play
  for each row execute function discard_stale_write();
```

Notes:

- `user_id default auth.uid()` means the client's PostgREST requests never
  send `user_id` explicitly (`SyncChangeSet`'s change types don't carry one)
  — Postgres fills it in from the authenticated request, and RLS's `with
  check` refuses a write for any other user's id outright even if a client
  tried.
- `SupabasePostgrestClient.upsert` sends `Prefer:
  resolution=merge-duplicates` — PostgREST's upsert-on-conflict, which
  performs an `insert ... on conflict (primary key) do update`. The `do
  update` branch is exactly what fires `discard_stale_write()`'s `before
  update` trigger, so first-time inserts and later updates both go through
  the same client call.
- Every response's HTTP status is checked (`SupabaseHttp.kt`). The shared
  Ktor client has `expectSuccess` off, and `upsert` returns `Unit`, so a
  rejected push used to be indistinguishable from an accepted one —
  `SyncEngine` would clear `isDirty` on rows the server never took and never
  retry them, leaving a library that looks synced and restores incomplete.
- `episode_play`'s primary key is the *derived* id
  `episodeId@watchedAtEpochMs`, not a per-device surrogate — see ADR 0013.
  It carries `deleted` because last-write-wins cannot express a hard delete:
  a physically removed row has no timestamp left to compare, so the other
  device's older copy would just be pushed back.
- `triage_decision` (ADR 0010) carries its own `title`/`poster_url` because a
  `SKIP` verdict writes no `collection_entry` row to join against — the
  Skipped screen renders straight off these. Unlike
  `collection_entry.notifications_muted`, it *is* synced: a skip is user
  intent, and a title ruled on from one device must not resurface on another.
- `triage_snooze` (ADR 0023) is a **separate table, not a fifth
  `triage_decision.verdict`**. An unknown verdict makes
  `TriageVerdict.fromStored` return null, so the row vanishes from every read
  while `selectDecidedIds` still counts it — an older client would exclude the
  title from its deck forever with no way to undo. A table it does not know
  about is simply never pulled. It carries the whole card snapshot
  (`title`/`year`/`poster_url`/`overview`) because the receiving device has to
  render it when the Snooze comes due and `MetadataProvider` has no cheap
  `summary(id)`; `due_at_epoch_day` is a day, not a timestamp. Deletes are
  soft, like `episode_play`'s, for the same reason.
- No `deleted` filter is applied server-side on select — `SyncEngine` pulls
  tombstoned rows too (so it can propagate the delete locally) and relies on
  `updated_at_epoch_ms` for the `since` filter, same as every other row.

## How a sync cycle works (EPIC 39, ADR 0020)

`SyncEngine.syncNow()` runs, under one mutex: **owner check, push, pull, reconcile**.

**Owner check.** `syncState.ownerAccountId` is the account this database's user
data belongs to. No owner (a fresh install, or any install from before it
existed) adopts the signed-in account, marks every local row dirty and resets
the cursors, so the first sync does one complete reconcile — this is what
uploads rewatch history from before plays synced. A *different* account returns
`SyncOutcome.AccountChanged` and moves nothing until
`resolveAccountChange(DiscardLocalData | MergeLocalDataIntoAccount)`. Plain
sign-out keeps the owner. `resyncEverything()` is the repair path: forget the
cursors, mark everything dirty, run a normal cycle.

**Push.** Table by table, parents first, at most 500 rows per request. Bodies
carry `null` explicitly (`"rating":null`) because PostgREST's merge-duplicates
upsert only writes the keys present in the body: an omitted key can never clear
a column. A chunk is marked clean only if the row is unchanged since it was
read (`clearDirty ... AND updatedAtEpochMs = :pushedAt`), so an edit made while
the request was on the wire is sent next time.

**Pull.** Per table, `order=server_seq.asc&limit=500&server_seq=gt.<cursor>`,
looped until an **empty** page: Supabase's `max_rows` cuts a response silently,
so a short page does not mean finished. Each page is applied in one
transaction. A pulled row replaces a local one when the local row is clean or
the pulled one is strictly newer than unsent edits; a winning `collectionEntry`
takes user fields only (favorite, rating, note, tombstone, add date) and keeps
this device's snapshot fields.

**Reconcile.** After every table has drained, for the titles touched: an
episode with `seen = 1` and no live play gets one at the tick's timestamp, and
an episode with `seen = 0` has its plays tombstoned; near-simultaneous
duplicate plays of one episode from different devices (within 5 minutes) are
merged down to the earliest, so a race between two unsynced ticks does not
read as a rewatch (issues #87/#97, ADR 0013's 2026-09-28 amendment); and
`airedEpisodes` is raised to the number ticked. Only then do the six cursors
move, in one transaction. A pull that fails anywhere moves none of them.

**Cursors** are `syncCursor(tableName, seq)`: one row per table, the last
`server_seq` applied, opaque to the engine. An install with no rows does a full
pull. `appSettings.syncCursorEpochMs` is dead.

**What a device leaves behind.** `syncState` records the last attempt's outcome,
error text, time and consecutive failures. Nothing reads it yet (EPIC 40).

### Snapshot refresh is not a synced write

`CollectionRepository.refreshSnapshot` (Library visit, pull-to-refresh, the
Android worker) updates provider data only and never touches `isDirty` or
`updatedAtEpochMs`, and only on a live row. `upsertSnapshot` is "add to
library" and is a synced write. Before EPIC 39 both were one call that stamped
`now` and dirtied the row, so a device that merely looked at the Library beat
other devices' real edits.

### Known limitations

- **Commit order of sequence values.** A row from a slow transaction can become
  visible behind a cursor that already passed its `server_seq`. Bounded by
  `resyncEverything()` and, once EPIC 40 lands, a weekly full reconcile.
- **Two edits to one row in the same millisecond** are indistinguishable to
  `clearDirty`'s guard.
- **An interrupted first sync** re-downloads from the start, since no cursor
  moves until everything drained.
- **`SyncOutcome.AccountChanged`** shows in Profile as a failed sync until
  EPIC 32 decides the account-switch policy and builds its confirmation.

## Automatic sync (EPIC 40, ADR 0021)

Off by default, and per device. Three layers must all pass, all enforced inside
`SyncEngine.syncNow(trigger)` by the pure `AutoSyncPolicy`:

| Layer | Where | If it fails |
|---|---|---|
| Build flag `SYNC_BACKGROUND_ENABLED` (on top of `SYNC_ENABLED` + keys) | `SyncAvailability.isBackgroundAvailable()` | no switch on Profile; every automatic trigger returns `Disabled` |
| The **Sync automatically** switch, `appSettings.syncAutomatically`, default off, never synced | `FeatureFlags.syncAutomatically` | every automatic trigger returns `Disabled`, touching neither database nor network |
| Session and entitlement | unchanged (ADR 0018) | `NotSignedIn` / `NotEntitled` |

`SyncTrigger` is `Manual | Foreground | Change | Periodic | Resume`. **Manual
always runs** (it skips the first two layers only).

| Trigger source | Mechanism | Limits |
|---|---|---|
| Local writes, all platforms | `SyncCoordinator` watches the dirty-row count | 5 s debounce; at most one change run per 30 s; backoff `30 s * 2^n` capped at 15 min; only while the process is alive |
| App foreground, all platforms | `MuvissApp`'s `AutoSyncOnForeground` -> `SyncCoordinator.onForeground()` on `ON_START` and `ON_RESUME` | skipped if a cycle finished under 60 s ago |
| Android | `SyncWorker` (WorkManager) | 1 h, unique work `background_sync`, `KEEP`, `CONNECTED` + battery not low. Scheduled and cancelled as the switch flips |
| iOS | inside `IosBackgroundRefresh.handle`, before the episode refresh | best effort, the system chooses when; no new `Info.plist` identifier |
| Desktop | timer in `Main.kt` next to `DesktopEpisodeRefresh` | 15 min, only while the window is open |
| Web | `visibilitychange` (to visible) and `online` -> `onResume()` | inert until web sign-in exists (#46) |

A weekly full pull bounds the sequence-gap risk from ADR 0020: once a week a cycle
resets the cursors and pulls from the beginning. Its timestamp is the reserved
`syncCursor` row `_lastFullPullAt`.

**What the Profile section shows.** The switch (only when the build has it, only
usable when signed in and entitled), a per-platform description ("in the
background" / "when the system allows" / "while Muviss is open"), a status line
(`Synced 3m ago`, then `4 changes waiting` or `Last sync failed: <reason>` with
**Retry**), **Resync everything** behind a confirmation, and "Session expired,
sign in again" when the session died. `AccountChanged` reads as a different
account owning this device's library and offers nothing to fix it (EPIC 32).

**Failures** are typed (`Offline | Unauthorised | Server | Unknown`); the reason
is the leading token of `syncState.lastError` (`REASON: diagnostic text`). Copy
is `SyncCopy`, never an exception's message.

### Verifying it by hand

None of these run in CI or on an emulator. Tests cover the policy, the
coordinator's timing in virtual time, the engine gating, the WorkManager result
mapper, the schedule controller, the desktop timer, the Profile section and the
whole flow against `FakeSupabaseServer`. These need a device or a project:

- **Android.** With `SYNC_BACKGROUND_ENABLED=true`, sign in, turn the switch on,
  then `adb shell dumpsys jobscheduler | grep -A4 muviss` shows the job. Run it:
  `adb shell cmd jobscheduler run -f com.codingpit.muviss <jobId>`. Turn the
  switch off and confirm the job is gone from `dumpsys`. Also build
  `assembleRelease` and run it once: a missing `-keep` rule shows up as
  `ClassNotFoundException` for `SyncWorker` only in a minified build.
- **iOS.** Run on a device or simulator, pause in the debugger and evaluate
  `e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"com.codingpit.muviss.refresh"]`. Expect `syncState` to update and the Profile line to say "Synced ...". Check the expiration path by suspending mid-run.
- **Desktop.** Package (`packageDistributionForCurrentOS`), sign in, switch on, leave the app open 20 minutes, check the status line advanced. Close it and confirm nothing runs.
- **Web.** Two tabs on the same origin: change tabs and back, toggle the network offline and online, watch the requests. Expect nothing until web sign-in exists (#46).
- **Live project.** The end-to-end flow against a real Supabase project, with `SYNC_ENTITLEMENT_OVERRIDE=true`, is still unverified (#88, #100).

## Entitlement: the server's half of the paid gate (EPIC 32, ADR 0019)

The `EntitlementGate` inside `SyncEngine` keeps an honest client honest. The
server enforces the same rule on its own, because the anon key ships in every
binary and a patched client skips any check it likes.

**Pieces** (all under `supabase/`):

| Piece | What it does |
|---|---|
| `public.entitlement(user_id, active, expires_at, updated_at, source_event, source_event_at)` | The mirror of RevenueCat's answer. RLS on; a client may **select its own row** and nothing else; there is no insert/update/delete policy, so only the service role writes. A trigger refuses an event older than the one already applied (`source_event_at`), because webhooks arrive at least once and in any order. |
| `public.custom_access_token_hook(event jsonb)` | Supabase Auth's custom access token hook, enabled in `config.toml` (`[auth.hook.custom_access_token]`). Runs on every token GoTrue mints (sign-in and every refresh) and stamps `sync_until` — the grant's end as `YYYY-MM-DDTHH:MM:SSZ`, or `9999-12-31T23:59:59Z` for an active grant with no end. Inactive, expired or absent → the claim is **omitted** (and stripped if it came in). |
| Every synced table's policy | `auth.uid() = user_id and (auth.jwt()->>'sync_until')::timestamptz > now()` in both `using` and `with check` (co-watch: `(auth.uid() = user_id or auth.uid() = recipient_id) and …` / `auth.uid() = user_id and …`). Still flat — ADR 0022's invariant 4. A missing claim casts to null and fails closed. |
| `functions/revenuecat-webhook` | RevenueCat → `entitlement`, as the service role. Checks `Authorization` against `REVENUECAT_WEBHOOK_SECRET` (constant time), only acts on entitlement id `sync`, requires `app_user_id` to be a Supabase uid, answers 200 for event types that do not change access. `REVENUECAT_ALLOWED_ENVIRONMENTS` (default `PRODUCTION`) decides whether `SANDBOX` purchases count. JWT verification is off for this function only. |
| `functions/delete-account` | `POST` with the caller's own access token. Resolves the user through GoTrue (`/auth/v1/user`) and hard-deletes exactly that user with the admin API; every table cascades from `auth.users`. Never takes a user id from the request. |

**Event mapping** (`revenuecat-webhook/logic.ts`): `INITIAL_PURCHASE`,
`RENEWAL`, `UNCANCELLATION`, `NON_RENEWING_PURCHASE`, `PRODUCT_CHANGE`,
`SUBSCRIPTION_EXTENDED`, `TEMPORARY_ENTITLEMENT_GRANT`, `REFUND_REVERSED` grant
until `expiration_at_ms` (null = no end). `CANCELLATION` and `BILLING_ISSUE`
keep access but move its end to `expiration_at_ms` (null = ends now, never
"forever"). `EXPIRATION` revokes. `TRANSFER` revokes every Supabase uid in
`transferred_from` and gives `transferred_to` the best live grant the sources
held (best effort; #281). Everything else, including unknown types, is a 200 no-op.

**What a client sees** (observed against the local stack):

| Situation | Result |
|---|---|
| Push (insert or `merge-duplicates` upsert over an existing row) without a valid `sync_until` | **HTTP 403**, PostgREST code **`42501`**, `new row violates row-level security policy for table "…"` |
| Pull without a valid `sync_until` | **HTTP 200 `[]`** — indistinguishable from "nothing new", so it must not be read as success when the engine already knows it is not entitled |
| Token minted before the purchase landed | still no claim: wait for the `entitlement` row to turn active, *then* refresh the session |
| Client writes `entitlement` | HTTP 403, `42501` `permission denied for table entitlement` |
| anon (no token) on any synced table or `entitlement` | HTTP 401, `42501` `permission denied for table …` (anon holds no privilege since `20261010000100`) |
| Push with a still-valid token after `delete-account` | HTTP 409, `23503` (the user row is gone) |

A lapse takes effect when the current token expires (`jwt_expiry`, one hour)
or `sync_until` passes, whichever is first. Server rows are kept, read-locked,
and readable again after a renewal.

**Developing against a project with these migrations**: `SYNC_ENTITLEMENT_OVERRIDE=true`
only opens the *client* gate. The server now also needs an `entitlement` row
for your user, written as `postgres` (SQL editor, or `psql` on the local stack):

```sql
insert into public.entitlement (user_id, active, expires_at, source_event)
values ('<your auth uid>', true, null, 'MANUAL:dev')
on conflict (user_id) do update set active = true, expires_at = null;
```

then sign out and in again (or wait for a refresh) so the token carries the
claim.

### Running the server tests locally

Local stack only — Docker, no project, nothing deployed. Neither suite runs in
CI yet (#280):

```bash
supabase start                      # once; applies config.toml (the hook included)
supabase db reset                   # every migration from empty
supabase test db                    # pgTAP: sync_server_seq + sync_entitlement
supabase stop
```

The Edge Functions' logic is tested with Deno, either installed or in a
container:

```bash
docker run --rm -v "$PWD/supabase/functions:/fn" -w /fn denoland/deno:2.1.4 \
  sh -c 'deno fmt --check && deno lint && deno check */index.ts && deno test --allow-net=jsr.io'
```

To exercise the functions against the local stack, put
`REVENUECAT_WEBHOOK_SECRET=…` (and `REVENUECAT_ALLOWED_ENVIRONMENTS=SANDBOX,PRODUCTION`)
in an env file outside the repo and run `supabase functions serve --env-file <it>`;
they answer on `http://127.0.0.1:54321/functions/v1/<name>`. A local user with
a password (admin API, `email_confirm: true`) signs in with
`/auth/v1/token?grant_type=password`, which is how the hook's claim can be
seen end to end.

### Applying it to a hosted project (not done; owner step, #282)

Nothing above has been deployed. Applying it is one change, in this order,
because the policies refuse every sync the moment they land:

1. `supabase db push` (both `20261010…` migrations).
2. Enable the hook on the project (Authentication → Hooks, or
   `supabase config push`) — without it no token carries `sync_until` and
   nobody can sync.
3. `supabase secrets set REVENUECAT_WEBHOOK_SECRET=…` and
   `supabase functions deploy revenuecat-webhook delete-account`.
4. In RevenueCat: webhook URL `https://<ref>.supabase.co/functions/v1/revenuecat-webhook`,
   Authorization header value = the same secret, entitlement identifier `sync`.
5. Insert an `entitlement` row for every account that should keep syncing
   (see above), then re-run `scripts/sync/verify-rls.sh`.

## Auth endpoints used

Plain HTTP against GoTrue (`{SUPABASE_URL}/auth/v1/...`), not the
`supabase-kt` SDK — see ADR 0009. Verified against
[GoTrue's OpenAPI spec](https://github.com/supabase/auth), not just SDK docs
(which don't publish the raw wire format):

| Endpoint | Used for |
|---|---|
| `POST /auth/v1/signup` (empty body) | `signInAnonymously` — GoTrue's anonymous sign-in shape, requires the project setting from step 2 above |
| `GET /auth/v1/authorize?provider=…&redirect_to=…&code_challenge=…&code_challenge_method=s256` | `beginOAuth` — **built, never requested**. It answers 302 to the provider, so following it from the HTTP client would authenticate the client rather than the user; the browser has to make this request |
| `POST /auth/v1/token?grant_type=pkce` `{ "auth_code", "code_verifier" }` | `completeOAuth` — redeems the `code` from the redirect. The verifier proves the code was issued to this device's attempt, which matters because `muviss://auth-callback` is a custom scheme any app can register (ADR 0014) |
| `POST /auth/v1/token?grant_type=refresh_token` `{ "refresh_token" }` | `refreshSession` — Supabase access tokens last about an hour, so without this a session stops syncing the same day it is created and only a re-login recovers. GoTrue **rotates** the refresh token on every use, so the returned one is the one to keep |
| `POST /auth/v1/logout` | `signOut` |

All require an `apikey: <anon key>` header; those acting on an existing
session additionally send `Authorization: Bearer <access_token>`.

## Verification status

Verified against the live project (`sodjedenvnvsuktbxevt`, eu-west-1) on
2026-08-29, on an Android device:

- **The schema applies.** All six tables with their primary keys and
  `(user_id, updated_at_epoch_ms)` indexes.
- **RLS blocks cross-user access.** An unauthenticated `GET` with the anon key
  returns `200 []` on every table and a `POST` is refused `401`. The `200` is
  the correct result, not a leak: `auth.uid()` is null without a token, so the
  policy matches no rows. A `200` *with rows* would be the leak.
- **OAuth round trip.** GitHub authorize → redirect → code exchange → session
  persisted, then a full push and pull. Device and server row counts matched
  exactly across all six tables.
- **`discard_stale_write()` fires on the upsert path.** A write with an older
  `updated_at_epoch_ms` returned `200` and left the row untouched; a newer one
  applied. This is the assumption the push-then-pull ordering rests on — push
  is unconditional *because* the server drops stale writes — and the 2xx on a
  discarded write is exactly why "the request succeeded" is not evidence.
- **Rewatch history crosses the wire**, carrying the derived ids `7.sqm`
  backfilled (ADR 0013).
- **The v7 → v8 migration runs on a real device**, against a seeded database:
  version bumped, `episodePlay` rebuilt, every row preserved with its id
  derived, `lastSyncedAtEpochMs` untouched.

Re-run any of these with `scripts/sync/` — see that directory's README.

Verified 2026-09-28, for EPIC 39's server-sequence work and EPIC 41's co-watch
RLS (#100, #88, #129) — partly against the live project (read-only SQL,
`supabase db query --linked`), partly against a local Docker stack running the
linked project's migrations byte-identical and from scratch (`supabase start`
+ `supabase test db --local`), since the live project intentionally has no
safe way to create throwaway test accounts (anonymous sign-in disabled, email
signup domain-restricted) — see EPIC 43 (#151), filed to give this a proper
home:

- **Server-seq backfill invariants hold on the live project.** No table has a
  null `server_seq`; `sync_change_seq`'s `last_value` is ≥ every table's max.
- **The pgTAP suite passes**: `sync_server_seq.test.sql`, 22/22 assertions,
  against migrations applied fresh from empty. This is also where
  `FakeSupabaseServer`'s two load-bearing claims get an independent check
  against the real triggers, not just the model: a discarded write is refused
  *and* keeps its old `server_seq`, so it never appears in anyone's feed.
- **Null clearing, both directions** (issue #88): a `merge-duplicates` upsert
  that omits a key never clears the column; the same upsert with the key set
  to `null` does. Confirms `SupabasePostgrestClient`'s `explicitNulls = true`
  is load-bearing, not incidental.
- **Co-watch RLS (#129), with three real accounts, not the fake.** An
  unlinked third account sees `200 []` on `cowatch_pool_entry` while two
  linked accounts see rows addressed to each other; a forged row (posting as
  a third account with someone else's `user_id`) is refused `403`; an
  unauthenticated `GET`/`POST` behave exactly as the six-table table above
  already describes. This is the actual claim ADR 0022 makes, tested for the
  first time.
- **Hosted `max_rows`** (issue #88) — the configured value itself is
  dashboard-only, not checkable via CLI/SQL; no table on the live project is
  within reach of even the local default of 1000 rows today, so this isn't
  currently biting anyone in practice.

**Still not run**: the Android `9.sqm` upgrade and first-sync-after-upgrade
reconcile against a real seeded device (#100 items 5-6) — needs a deliberate
device pass, not attempted this round.

## Still open

- **Token refresh against a live session.** The logic is covered by
  `SupabaseSyncBackendTest` over a `MockEngine`, but nothing has yet watched a
  real Supabase token cross its one-hour expiry. Sign in, leave the app more
  than an hour, return, and confirm sync still succeeds.
- **Fresh-install restore** (issue #8's acceptance criterion). Clearing the app
  data and signing in again should bring the whole library back from the
  server. The pull path is exercised, but not from an empty database.
