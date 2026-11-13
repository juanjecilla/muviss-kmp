-- EPIC 51 / ADR 0036 (#644): Muted Genres sync across the account, as decisions and a choice marker.
--
-- `muted_genre_choice` is the marker: one row per account, present once the person has chosen their muted set,
-- with `defaults_at_choice`, the default set in force at that choice. A device with no marker follows the code's
-- defaults. It is never removed, so it has no `deleted` column.
--
-- `muted_genre` is one row per genre the person has decided about, keyed by media type and TMDB genre id. A live
-- row mutes the genre whatever its default; a tombstone (`deleted`) unmutes it. Soft deletes are the same as
-- `triage_snooze`'s: a hard delete leaves no timestamp for last-write-wins to compare, so a device holding an older
-- live row would push it back and re-mute a genre another device unmuted.
--
-- Both are single-owner tables with the flat "own rows only" policy, the LWW trigger that stamps and clamps (ADR
-- 0020) and a `(user_id, server_seq)` paging index. The sync-entitlement gate (PR #283) is a separate change.
create table if not exists public.muted_genre_choice (
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade primary key,
  defaults_at_choice text not null,
  updated_at_epoch_ms bigint not null,
  server_seq bigint not null
);

create table if not exists public.muted_genre (
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  media_type text not null,
  genre_id bigint not null,
  name text not null,
  deleted boolean not null default false,
  updated_at_epoch_ms bigint not null,
  server_seq bigint not null,
  primary key (user_id, media_type, genre_id)
);

alter table public.muted_genre_choice enable row level security;
alter table public.muted_genre enable row level security;

create policy "own rows only" on public.muted_genre_choice
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create policy "own rows only" on public.muted_genre
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

drop trigger if exists muted_genre_choice_lww on public.muted_genre_choice;
create trigger muted_genre_choice_lww
  before insert or update on public.muted_genre_choice
  for each row execute function discard_stale_write();

drop trigger if exists muted_genre_lww on public.muted_genre;
create trigger muted_genre_lww
  before insert or update on public.muted_genre
  for each row execute function discard_stale_write();

create index if not exists muted_genre_choice_seq on public.muted_genre_choice(user_id, server_seq);
create index if not exists muted_genre_seq on public.muted_genre(user_id, server_seq);
