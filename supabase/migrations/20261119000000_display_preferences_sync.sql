-- EPIC 51 / ADR 0036 (#643): the Display Preferences group syncs across the account.
--
-- One row per account, because the group is stamped as one: any member's change moves the whole group's
-- `updated_at_epoch_ms`, and per-field last-write-wins was rejected for ten stamps and ten rows (ADR 0036).
-- The primary key is `user_id`, so there is no `deleted` column: the group is never removed, a person
-- resets a value by changing it.
--
-- Every member is NOT NULL and always sent, so none has a default. Device switches (notifications, sync,
-- crash reports, OS backup, the one-time introductions) are not here on purpose: they describe this device.
--
-- Same last-write-wins stamping, clamping and `server_seq` as every other single-owner table (ADR 0020),
-- and the same flat "own rows only" policy as `triage_snooze`. The sync-entitlement gate (PR #283) is a
-- separate change and is not made here; see docs/SYNC.md.
create table if not exists public.display_preferences (
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade primary key,
  theme text not null,
  language text not null,
  region text not null,
  animations_enabled boolean not null,
  triage_deck_animations boolean not null,
  tick_animations boolean not null,
  triage_control_scheme text not null,
  triage_snooze_period text not null,
  triage_snooze_placement text not null,
  muted_genres_in_search boolean not null,
  updated_at_epoch_ms bigint not null,
  server_seq bigint not null
);

alter table public.display_preferences enable row level security;

create policy "own rows only" on public.display_preferences
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

drop trigger if exists display_preferences_lww on public.display_preferences;
create trigger display_preferences_lww
  before insert or update on public.display_preferences
  for each row execute function discard_stale_write();

-- The paging index, matching every other table the pull reads.
create index if not exists display_preferences_seq on public.display_preferences(user_id, server_seq);
