-- Muviss sync schema (ADR 0009, extended by ADR 0013).
--
-- Six tables, one per synced local table in core/database's .sq files, each
-- keyed by (user_id, <the local table's own natural key>). Column names are
-- snake_case versions of the local ones — PostgREST convention, and
-- SyncChangeSet's @SerialName annotations match these exactly, so a rename
-- here silently stops that column syncing.
--
-- This file is the source of truth; docs/SYNC.md explains the reasoning behind
-- it. Until now the schema existed only as SQL fenced in that Markdown file,
-- which meant standing up a project was a copy-paste job with no record of
-- what had actually been applied. Apply with `supabase db push`.

-- Shared last-write-wins guard: silently discards an incoming write whose
-- updated_at_epoch_ms is older than what's already stored, by rewriting NEW
-- back to OLD rather than raising — the client's upsert still returns 200, it
-- just didn't move anything. This is what lets SyncEngine push
-- unconditionally (no read-before-write) and still converge correctly
-- regardless of which device's push physically arrives first — see ADR 0009's
-- "Engine order is push, then pull" section.
create or replace function discard_stale_write()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if new.updated_at_epoch_ms < old.updated_at_epoch_ms then
    return old;
  end if;
  return new;
end;
$$;

-- collection_entry --
-- notifications_muted is deliberately absent: a per-device notification
-- preference, not library data (ADR 0009).
create table if not exists public.collection_entry (
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

alter table public.collection_entry enable row level security;

create policy "own rows only" on public.collection_entry
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create trigger collection_entry_lww
  before update on public.collection_entry
  for each row execute function discard_stale_write();

-- episode_progress --
-- No `deleted` column: ticks are idempotent booleans, and un-ticking sets
-- seen = false, which is itself the change that propagates (ADR 0009).
create table if not exists public.episode_progress (
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  episode_id text not null,
  media_id text not null,
  season_number integer not null,
  episode_number integer not null,
  seen boolean not null default false,
  updated_at_epoch_ms bigint not null,
  primary key (user_id, episode_id)
);

alter table public.episode_progress enable row level security;

create policy "own rows only" on public.episode_progress
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create trigger episode_progress_lww
  before update on public.episode_progress
  for each row execute function discard_stale_write();

-- media_list --
create table if not exists public.media_list (
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  id text not null,
  name text not null,
  created_at_epoch_ms bigint not null,
  updated_at_epoch_ms bigint not null,
  deleted boolean not null default false,
  primary key (user_id, id)
);

alter table public.media_list enable row level security;

create policy "own rows only" on public.media_list
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create trigger media_list_lww
  before update on public.media_list
  for each row execute function discard_stale_write();

-- list_entry --
create table if not exists public.list_entry (
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  list_id text not null,
  media_id text not null,
  added_at_epoch_ms bigint not null,
  updated_at_epoch_ms bigint not null,
  deleted boolean not null default false,
  primary key (user_id, list_id, media_id)
);

alter table public.list_entry enable row level security;

create policy "own rows only" on public.list_entry
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create trigger list_entry_lww
  before update on public.list_entry
  for each row execute function discard_stale_write();

-- triage_decision --
-- Carries its own title/poster_url because a SKIP verdict writes no
-- collection_entry row to join against (ADR 0010). Unlike
-- collection_entry.notifications_muted it IS synced: a skip is user intent,
-- and a title ruled on from one device must not resurface on another.
create table if not exists public.triage_decision (
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

alter table public.triage_decision enable row level security;

create policy "own rows only" on public.triage_decision
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create trigger triage_decision_lww
  before update on public.triage_decision
  for each row execute function discard_stale_write();

-- episode_play -- (ADR 0013)
-- `id` is the derived key `episodeId@watchedAtEpochMs`, not a per-device
-- surrogate: an AUTOINCREMENT id means device A's play #5 and device B's play
-- #5 are different viewings, so it could never be a sync key. `deleted` is
-- present because last-write-wins cannot express a hard delete — a physically
-- removed row has no timestamp left to compare, so the other device's older
-- copy would simply be pushed back and "clear watch history" would undo itself.
create table if not exists public.episode_play (
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  id text not null,
  episode_id text not null,
  media_id text not null,
  watched_at_epoch_ms bigint not null,
  updated_at_epoch_ms bigint not null,
  deleted boolean not null default false,
  primary key (user_id, id)
);

alter table public.episode_play enable row level security;

create policy "own rows only" on public.episode_play
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create trigger episode_play_lww
  before update on public.episode_play
  for each row execute function discard_stale_write();

-- SyncEngine pulls with `updated_at_epoch_ms=gt.<cursor>` on every table, and
-- filters nothing else server-side (it pulls tombstones too, so it can
-- propagate the delete locally). These indexes are what keep that a range scan
-- rather than a sequential one as a library grows.
create index if not exists collection_entry_updated_at on public.collection_entry (user_id, updated_at_epoch_ms);
create index if not exists episode_progress_updated_at on public.episode_progress (user_id, updated_at_epoch_ms);
create index if not exists media_list_updated_at on public.media_list (user_id, updated_at_epoch_ms);
create index if not exists list_entry_updated_at on public.list_entry (user_id, updated_at_epoch_ms);
create index if not exists triage_decision_updated_at on public.triage_decision (user_id, updated_at_epoch_ms);
create index if not exists episode_play_updated_at on public.episode_play (user_id, updated_at_epoch_ms);
