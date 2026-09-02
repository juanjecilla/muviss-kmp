# Sync & accounts (EPIC 9)

The optional cloud sync layer: `SyncEngine` (`:core:sync`) replays local
dirty rows to a backend and merges remote changes back in. See ADR 0009 for
the design rationale (multi-backend seam, plain Ktor over `supabase-kt`,
last-write-wins conflict resolution) — this document is the concrete
Supabase project setup and the SQL schema `SupabaseSyncBackend` talks to.

Sync is entirely **optional**, and gated twice (ADR 0012):

1. **Build gate** — `SYNC_ENABLED` *and* both Supabase keys must be present.
   Miss any of the three and `SyncAvailability` reports unconfigured,
   `di/SyncModule.kt` binds `NoOpSyncBackend`, and the profile screen hides
   the sync section outright. Nothing else about the app changes. Release CI
   sets none of them, which is what keeps sync out of production.
2. **Entitlement gate** — sync is a paid feature. `SyncEngine.syncNow()`
   consults an `EntitlementGate` and returns `NotEntitled` if the user has not
   paid, so `MuvissApp`'s foreground auto-sync is covered too. With no store
   wired up (every build today) `:core:billing` binds `NoEntitlementProvider`,
   which reports Inactive — set `SYNC_ENTITLEMENT_OVERRIDE=true` in
   `local.properties` to grant it to your own build.

An unavailable build renders no sync UI at all; an unentitled one renders the
row and a paywall. Those are deliberately opposite, see ADR 0012.

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
   SUPABASE_URL=https://your-project.supabase.co
   SUPABASE_ANON_KEY=your-anon-key
   ```

   Note that Supabase's free tier **pauses a project after 7 days of
   inactivity**. A paused project fails in a way that reads like an app bug;
   check the dashboard before debugging the client.

   Same generated-constant mechanism as `TMDB_API_KEY` and `SENTRY_DSN` (ADR
   0007) — read by `core/sync/build.gradle.kts` at build time, baked into
   `MuvissBuildConfig`, never committed. CI and any clone without these two
   properties builds and runs exactly as before, with sync hidden.

## Schema

> Reference copy. The executable one is
> `supabase/migrations/20260829000000_sync_schema.sql`, applied with
> `supabase db push` — see step 4 above. That file additionally creates a
> `(user_id, updated_at_epoch_ms)` index per table, which is what keeps
> `SyncEngine`'s `gt.<cursor>` pull a range scan as a library grows.

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
- No `deleted` filter is applied server-side on select — `SyncEngine` pulls
  tombstoned rows too (so it can propagate the delete locally) and relies on
  `updated_at_epoch_ms` for the `since` filter, same as every other row.

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

## Still open

- **Token refresh against a live session.** The logic is covered by
  `SupabaseSyncBackendTest` over a `MockEngine`, but nothing has yet watched a
  real Supabase token cross its one-hour expiry. Sign in, leave the app more
  than an hour, return, and confirm sync still succeeds.
- **Fresh-install restore** (issue #8's acceptance criterion). Clearing the app
  data and signing in again should bring the whole library back from the
  server. The pull path is exercised, but not from an empty database.
