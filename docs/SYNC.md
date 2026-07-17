# Sync & accounts (EPIC 9)

The optional cloud sync layer: `SyncEngine` (`:core:sync`) replays local
dirty rows to a backend and merges remote changes back in. See ADR 0009 for
the design rationale (multi-backend seam, plain Ktor over `supabase-kt`,
last-write-wins conflict resolution) — this document is the concrete
Supabase project setup and the SQL schema `SupabaseSyncBackend` talks to.

Sync is entirely **optional**: with no keys configured, `SyncAvailability`
reports unconfigured, `di/SyncModule.kt` binds `NoOpSyncBackend`, and the
profile screen hides the sync section outright. Nothing else about the app
changes.

## Setting up a Supabase project

1. Create a free project at [supabase.com](https://supabase.com).
2. **Enable anonymous sign-ins** (Authentication → Providers → Anonymous
   Sign-Ins) if you intend to use `SyncBackend.signInAnonymously` — the
   shipped Profile UI only wires the email one-time-code flow, so this is
   optional for the app as it stands today.
3. **Email OTP**: Authentication → Providers → Email is on by default;
   confirm "Confirm email" / OTP length settings match what you want users to
   see (Muviss expects a 6-digit code, GoTrue's default).
4. Run the SQL below in the SQL Editor (Database → SQL Editor) to create the
   four synced tables, their Row Level Security policies, and the
   last-write-wins trigger.
5. Copy the project's URL and anon (public) key (Project Settings → API)
   into `local.properties` (gitignored, never commit real keys):

   ```properties
   SUPABASE_URL=https://your-project.supabase.co
   SUPABASE_ANON_KEY=your-anon-key
   ```

   Same generated-constant mechanism as `TMDB_API_KEY` and `SENTRY_DSN` (ADR
   0007) — read by `core/sync/build.gradle.kts` at build time, baked into
   `MuvissBuildConfig`, never committed. CI and any clone without these two
   properties builds and runs exactly as before, with sync hidden.

## Schema

One table per synced local table (`collectionEntry`, `episodeProgress`,
`mediaList`, `listEntry` — see their `.sq` files in `core/database`), each
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
| `POST /auth/v1/logout` | `signOut` |

All four also require an `apikey: <anon key>` header; the three that act on
an existing session additionally send `Authorization: Bearer <access_token>`.

## What's not verified

This pass has no live Supabase project wired up — `SyncEngine` is tested
against an in-memory `FakeSyncBackend` only (see `core/sync/src/jvmTest`),
by design (no live network calls in tests). Before relying on this in
production, provision a real project per the steps above and manually check:

- Email OTP round trip (`requestEmailOtp` → real inbox → `verifyEmailOtp`)
  against GoTrue's actual response shapes.
- The `discard_stale_write()` trigger actually fires on a `resolution=merge-
  duplicates` upsert as described (verified against PostgREST's documented
  behavior, not against a running project).
- RLS policies block cross-user access as intended.
- A fresh install signing in restores the full library + progress (issue
  #8's acceptance criterion) end to end against real data.
