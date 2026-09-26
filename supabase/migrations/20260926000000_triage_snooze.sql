-- EPIC 42 (snooze, ADR 0023): postponed triage decisions.
--
-- A single-owner table, so it takes the same flat policy the six original
-- tables carry rather than co-watch's addressed-row shape: a Snooze is one
-- account's own intent and no other account ever reads it.
--
-- Deliberately a table of its own rather than a new `triage_decision.verdict`
-- value. Locally, an unknown verdict makes `TriageVerdict.fromStored` return
-- null, so the row vanishes from every read while still counting as decided —
-- a client that predates this feature would exclude the title from its deck
-- forever with no screen able to show or undo it. A table it does not know
-- about is simply never pulled.
--
-- The card snapshot (title/year/poster_url/overview) is stored because the
-- receiving device has to render the card again on the due date and cannot
-- cheaply re-derive it from the provider — see ADR 0023.
--
-- `due_at_epoch_day` is a DAY, not a timestamp: the title comes back for the
-- whole of that date in each device's own reckoning.
--
-- Nothing has shipped against this schema — sync is gated off in every release
-- build (ADR 0018) — which is what makes adding a table this way safe.
create table if not exists public.triage_snooze (
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
  server_seq bigint,
  primary key (user_id, media_id)
);

alter table public.triage_snooze enable row level security;

create policy "own rows only" on public.triage_snooze
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- Same last-write-wins stamping and clamping as every other table (ADR 0020).
-- `discard_stale_write()` also assigns `server_seq`, which is what makes this
-- table pageable by the same cursor machinery.
drop trigger if exists triage_snooze_lww on public.triage_snooze;
create trigger triage_snooze_lww
  before insert or update on public.triage_snooze
  for each row execute function discard_stale_write();

-- Paging index, matching the six single-owner tables.
create index if not exists triage_snooze_seq on public.triage_snooze(user_id, server_seq);
