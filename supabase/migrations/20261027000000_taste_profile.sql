-- EPIC 51 (#402): the Taste Profile, what the person says they like.
--
-- A single-owner table, so it takes the same flat policy as `triage_snooze`
-- (ADR 0023): one account's own answers, which no other account reads. The
-- shape is one row per item, not one row per person with a list column, so
-- two devices each adding an item both keep it (last-write-wins on a whole
-- list would drop one of them). The `id` is the derived `kind:value` key the
-- client computes, so an item recorded twice is one row.
--
-- Soft deletes, like `triage_snooze`: an unpicked item is a tombstone with a
-- newer stamp, so the other device cannot push it back. `label` is stored so a
-- device that pulls the row can name it without asking TMDB.
--
-- Nothing has shipped against this schema (sync is gated off in every release
-- build, ADR 0018), which is what makes adding the table this way safe.
create table if not exists public.taste_profile (
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  id text not null,
  kind text not null,
  value text not null,
  label text not null default '',
  updated_at_epoch_ms bigint not null,
  deleted boolean not null default false,
  server_seq bigint,
  primary key (user_id, id)
);

alter table public.taste_profile enable row level security;

create policy "own rows only" on public.taste_profile
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- Same last-write-wins stamping and clamping as every other table (ADR 0020).
-- `discard_stale_write()` also assigns `server_seq`, which makes the table
-- pageable by the same cursor machinery.
drop trigger if exists taste_profile_lww on public.taste_profile;
create trigger taste_profile_lww
  before insert or update on public.taste_profile
  for each row execute function discard_stale_write();

-- Paging index, matching the other single-owner tables.
create index if not exists taste_profile_seq on public.taste_profile(user_id, server_seq);
