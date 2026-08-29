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
   Sign-Ins) if you intend to use `SyncBackend.signInAnonymously` — the
   shipped Profile UI only wires the email one-time-code flow, so this is
   optional for the app as it stands today.
3. **Email OTP — the stock templates do not work, and this is the trap.**
   Authentication → Providers → Email is on by default, but GoTrue's default
   `confirmation` and `magic_link` templates both render
   `{{ .ConfirmationURL }}`: the user gets a clickable *link*, while Muviss's
   profile screen asks them to type a *code*. There is nothing in the email
   to type, and following the link instead bounces off `site_url` with
   `error_code=otp_expired`, which reads like a broken token rather than a
   template mismatch. The OTP itself is fine — it is simply never shown.

   The fix is `{{ .Token }}` in both templates. Both, not one:
   `POST /auth/v1/otp` sends `magic_link` to an address that already has a
   user and `confirmation` to a new one, and Muviss passes
   `create_user: true`. Both live in `supabase/templates/`, wired up in
   `supabase/config.toml`, and apply with:

   ```bash
   supabase config push
   ```

   **On the free tier that push is refused** unless the project uses custom
   SMTP:

   > Email template modification is not available for free tier projects
   > using the default email provider. Please upgrade your plan or configure
   > a custom SMTP provider.

   So sign-in needs a custom SMTP provider (Authentication → Emails → SMTP
   Settings) before it can work at all. That is worth doing regardless: the
   built-in sender is rate-limited to a couple of emails an hour and Supabase
   documents it as unsuitable for production.

   Two further settings worth knowing while testing: `max_frequency` is one
   minute, so asking for a second code too quickly is rejected in a way that
   looks like the app failing; and the project ships `otp_length = 8` while
   this repo's config and docs assume 6 — `supabase config push` reconciles
   that in the same call as the templates, since the auth update is atomic
   and currently fails as a whole.
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
| `POST /auth/v1/otp` `{ "email", "create_user": true }` | `requestEmailOtp` |
| `POST /auth/v1/verify` `{ "type": "email", "email", "token" }` | `verifyEmailOtp` — `type: "email"` is GoTrue's OTP-code verification path, distinct from `"magiclink"` |
| `POST /auth/v1/token?grant_type=refresh_token` `{ "refresh_token" }` | `refreshSession` — Supabase access tokens last about an hour, so without this a session stops syncing the same day it is created and only a re-login recovers. GoTrue **rotates** the refresh token on every use, so the returned one is the one to keep |
| `POST /auth/v1/logout` | `signOut` |

All four also require an `apikey: <anon key>` header; the three that act on
an existing session additionally send `Authorization: Bearer <access_token>`.

## What's not verified

`SupabaseSyncBackend` now has its own tests (`SupabaseSyncBackendTest`, over a
Ktor `MockEngine`) covering status handling, token refresh, refresh-on-401 and
the sign-out-on-dead-refresh path; `SyncEngine` is still tested against the
in-memory `FakeSyncBackend`. Neither talks to a network, by design.

**Verified against the live project** (`sodjedenvnvsuktbxevt`, eu-west-1) on
2026-08-29:

- The migration applies. All six tables exist with their primary keys and
  `(user_id, updated_at_epoch_ms)` indexes.
- **RLS blocks cross-user access.** An unauthenticated `GET` with the anon key
  returns `200 []` on every table, and a `POST` is refused with `401`. The
  `200` is the correct result, not a leak: `auth.uid()` is null for an
  unauthenticated request, so the `using (auth.uid() = user_id)` policy matches
  no rows and PostgREST returns an empty set rather than an error. A `200` with
  rows in it would be the leak.

What no test can establish is whether the rest of the server behaves as
assumed. Still open, and each needs a real signed-in session:

- **Email OTP round trip** (`requestEmailOtp` → real inbox → `verifyEmailOtp`)
  against GoTrue's actual response shapes. Nothing before this point exercises
  a real GoTrue response. Attempted 2026-08-29 and **blocked**: the request
  side works — a real email arrived — but the stock template carries a link
  rather than a code, so there was nothing to type, and the link failed with
  `otp_expired`. Blocked on custom SMTP; see step 3.
- **Token refresh end to end.** Sign in, leave the app more than an hour,
  return, and confirm sync still succeeds. The client-side logic is tested;
  what is not is whether GoTrue's `/token` response deserializes into
  `GoTrueSessionDto` as expected.
- **Failure actually surfaces.** Break the anon key on purpose, sync, and
  confirm the profile screen reports a failure rather than "Synced just now".
- The `discard_stale_write()` trigger actually fires on a `resolution=merge-
  duplicates` upsert as described (verified against PostgREST's documented
  behavior, not against a running project). This is the one the whole
  push-then-pull ordering rests on: push is unconditional precisely because the
  server is supposed to discard a stale write, so if the trigger does not fire
  on the upsert path, two devices silently stop converging.
- A fresh install signing in restores the full library + progress (issue
  #8's acceptance criterion) end to end against real data.
- **Rewatch history crosses devices** (ADR 0013): tick an episode twice on one
  device, sync both, confirm the other reads "watched 2x"; then clear the
  history on the second and confirm the first drops to zero.
- With `SYNC_ENABLED` unset, a release build shows **no sync row at all** on
  the profile screen. Confirm `SYNC_ENABLED`, `SYNC_ENTITLEMENT_OVERRIDE` and
  both Supabase keys are absent from `.github/workflows/release.yml`'s `env:`.
