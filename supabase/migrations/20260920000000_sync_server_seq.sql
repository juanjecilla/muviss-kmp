-- EPIC 39 (sync correctness, ADR 0020): a server-assigned change sequence.
--
-- The pull cursor used to be the maximum `updated_at_epoch_ms` a client had
-- seen. That is a maximum of *client clocks*, and it is the wrong thing to page
-- a change feed by: a device that pushes late (an offline edit older than the
-- cursor) is never seen; a device with a fast clock pushes the cursor past
-- everything honest devices write afterwards; rows stamped in the same
-- millisecond and pushed in separate requests are lost at the `gt` boundary.
--
-- `server_seq` fixes the feed, not the clocks: every accepted write takes the
-- next value of one sequence, in the order the server applied it, and clients
-- page by `order=server_seq.asc&server_seq=gt.<n>`. `updated_at_epoch_ms`
-- stays what it always was — the last-write-wins tiebreak between two devices'
-- edits — and the trigger below now also clamps it so one wrong clock cannot
-- win forever.
--
-- Nothing has shipped against this schema (sync is gated off in every release),
-- which is what makes a migration this shape safe to apply.

create sequence if not exists public.sync_change_seq;

-- 1. The column, nullable until it is backfilled.
alter table public.collection_entry add column if not exists server_seq bigint;
alter table public.episode_progress add column if not exists server_seq bigint;
alter table public.media_list add column if not exists server_seq bigint;
alter table public.list_entry add column if not exists server_seq bigint;
alter table public.triage_decision add column if not exists server_seq bigint;
alter table public.episode_play add column if not exists server_seq bigint;

-- 2. Backfill, in `updated_at_epoch_ms` order per table, so an existing row keeps
-- its place relative to its neighbours. Done *before* the stamping trigger is
-- replaced: the current trigger passes an update with an unchanged timestamp
-- through untouched, whereas the new one would restamp every row it backfills.
-- Dense 1..n per table is enough (a client only compares within a table); the
-- shared sequence is then moved past the highest of them so every later write,
-- on any table, sorts after every backfilled row.
update public.collection_entry t set server_seq = o.rn
from (select user_id, media_id, row_number() over (order by updated_at_epoch_ms, user_id, media_id) as rn from public.collection_entry) o
where t.user_id = o.user_id and t.media_id = o.media_id and t.server_seq is null;

update public.episode_progress t set server_seq = o.rn
from (select user_id, episode_id, row_number() over (order by updated_at_epoch_ms, user_id, episode_id) as rn from public.episode_progress) o
where t.user_id = o.user_id and t.episode_id = o.episode_id and t.server_seq is null;

update public.media_list t set server_seq = o.rn
from (select user_id, id, row_number() over (order by updated_at_epoch_ms, user_id, id) as rn from public.media_list) o
where t.user_id = o.user_id and t.id = o.id and t.server_seq is null;

update public.list_entry t set server_seq = o.rn
from (select user_id, list_id, media_id, row_number() over (order by updated_at_epoch_ms, user_id, list_id, media_id) as rn from public.list_entry) o
where t.user_id = o.user_id and t.list_id = o.list_id and t.media_id = o.media_id and t.server_seq is null;

update public.triage_decision t set server_seq = o.rn
from (select user_id, media_id, row_number() over (order by updated_at_epoch_ms, user_id, media_id) as rn from public.triage_decision) o
where t.user_id = o.user_id and t.media_id = o.media_id and t.server_seq is null;

update public.episode_play t set server_seq = o.rn
from (select user_id, id, row_number() over (order by updated_at_epoch_ms, user_id, id) as rn from public.episode_play) o
where t.user_id = o.user_id and t.id = o.id and t.server_seq is null;

select setval(
  'public.sync_change_seq',
  greatest(
    1,
    coalesce((select max(server_seq) from public.collection_entry), 0),
    coalesce((select max(server_seq) from public.episode_progress), 0),
    coalesce((select max(server_seq) from public.media_list), 0),
    coalesce((select max(server_seq) from public.list_entry), 0),
    coalesce((select max(server_seq) from public.triage_decision), 0),
    coalesce((select max(server_seq) from public.episode_play), 0)
  )
);

-- 3. From here every row has one.
alter table public.collection_entry alter column server_seq set not null;
alter table public.episode_progress alter column server_seq set not null;
alter table public.media_list alter column server_seq set not null;
alter table public.list_entry alter column server_seq set not null;
alter table public.triage_decision alter column server_seq set not null;
alter table public.episode_play alter column server_seq set not null;

-- 4. The pull is `where user_id = auth.uid() and server_seq > $n order by
-- server_seq limit 500`, which is exactly this index. The old
-- `(user_id, updated_at_epoch_ms)` indexes served the pull this replaces; they
-- are left in place rather than dropped, since dropping is a decision for once
-- the new feed has run against a real project.
create index if not exists collection_entry_server_seq on public.collection_entry (user_id, server_seq);
create index if not exists episode_progress_server_seq on public.episode_progress (user_id, server_seq);
create index if not exists media_list_server_seq on public.media_list (user_id, server_seq);
create index if not exists list_entry_server_seq on public.list_entry (user_id, server_seq);
create index if not exists triage_decision_server_seq on public.triage_decision (user_id, server_seq);
create index if not exists episode_play_server_seq on public.episode_play (user_id, server_seq);

-- 5. One trigger function does three jobs, in this order, on every insert and
-- update of a synced table:
--
--   a. Clamp. `new.updated_at_epoch_ms` is capped at server time plus 60
--      seconds. A device whose clock is a day fast used to stamp rows a day in
--      the future, and last-write-wins then rejected every honest edit to them
--      until the day was up. Now such a row wins for at most a minute.
--   b. Discard a stale write. As before: an update whose (clamped) stamp is
--      strictly older than the stored row's is rewritten back to the old row,
--      and the client's upsert still gets a 2xx. Returning `old` also keeps the
--      old `server_seq`, which matters: a discarded write must not appear in
--      anyone's change feed as if it were a change.
--   c. Stamp. An accepted write takes the next value of the shared sequence.
--      This overrides whatever the client sent, so a client cannot place a row
--      behind another device's cursor.
--
-- An upsert that lands on an existing row runs the insert trigger first (the
-- proposed row) and then the update trigger, so it draws two values from the
-- sequence. Gaps are normal; a client must never assume the sequence is dense.
create or replace function discard_stale_write()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  ceiling bigint := (extract(epoch from pg_catalog.clock_timestamp()) * 1000)::bigint + 60000;
begin
  if new.updated_at_epoch_ms > ceiling then
    new.updated_at_epoch_ms := ceiling;
  end if;
  if tg_op = 'UPDATE' and new.updated_at_epoch_ms < old.updated_at_epoch_ms then
    return old;
  end if;
  new.server_seq := nextval('public.sync_change_seq');
  return new;
end;
$$;

-- The triggers were `before update` only; stamping and clamping need the insert
-- path too.
drop trigger if exists collection_entry_lww on public.collection_entry;
create trigger collection_entry_lww
  before insert or update on public.collection_entry
  for each row execute function discard_stale_write();

drop trigger if exists episode_progress_lww on public.episode_progress;
create trigger episode_progress_lww
  before insert or update on public.episode_progress
  for each row execute function discard_stale_write();

drop trigger if exists media_list_lww on public.media_list;
create trigger media_list_lww
  before insert or update on public.media_list
  for each row execute function discard_stale_write();

drop trigger if exists list_entry_lww on public.list_entry;
create trigger list_entry_lww
  before insert or update on public.list_entry
  for each row execute function discard_stale_write();

drop trigger if exists triage_decision_lww on public.triage_decision;
create trigger triage_decision_lww
  before insert or update on public.triage_decision
  for each row execute function discard_stale_write();

drop trigger if exists episode_play_lww on public.episode_play;
create trigger episode_play_lww
  before insert or update on public.episode_play
  for each row execute function discard_stale_write();
