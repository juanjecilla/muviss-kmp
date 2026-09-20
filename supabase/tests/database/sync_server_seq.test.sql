-- pgTAP tests for supabase/migrations/20260920000000_sync_server_seq.sql
-- (EPIC 39, ADR 0020). Run with `supabase test db`, which needs Docker.
--
-- NOT RUN when this was written: the machine had no Docker and no project.
-- The assertions mirror `FakeSupabaseServer` in :core:testing, which is a
-- hand-written model of these triggers, so a failure here means either the SQL
-- or that model is wrong. Fix whichever it is and keep the two in step.
--
-- Everything runs inside one transaction that is rolled back, so it leaves the
-- database as it found it.
begin;

select plan(22);

-- Two users. The `authenticated` role plus request.jwt.claim.sub is what
-- auth.uid() reads, which is what the tables' `default auth.uid()` and their
-- RLS policies use.
insert into auth.users (id, aud, role, email)
values
  ('00000000-0000-0000-0000-00000000000a', 'authenticated', 'authenticated', 'alice@example.test'),
  ('00000000-0000-0000-0000-00000000000b', 'authenticated', 'authenticated', 'bob@example.test');

-- Acting as a user is three statements: set the claims auth.uid() reads, then
-- drop to the `authenticated` role so RLS applies. `reset role` first when
-- switching between users.

-- --------------------------------------------------------------- structure ---

select col_not_null('public', 'collection_entry', 'server_seq', 'collection_entry.server_seq is not null');
select col_not_null('public', 'episode_progress', 'server_seq', 'episode_progress.server_seq is not null');
select col_not_null('public', 'media_list', 'server_seq', 'media_list.server_seq is not null');
select col_not_null('public', 'list_entry', 'server_seq', 'list_entry.server_seq is not null');
select col_not_null('public', 'triage_decision', 'server_seq', 'triage_decision.server_seq is not null');
select col_not_null('public', 'episode_play', 'server_seq', 'episode_play.server_seq is not null');

select has_index('public', 'collection_entry', 'collection_entry_server_seq', array['user_id', 'server_seq'], 'the pull is served by an index on (user_id, server_seq)');
select has_index('public', 'episode_play', 'episode_play_server_seq', array['user_id', 'server_seq'], 'so is every other table');

-- ---------------------------------------------------------- monotonic seq ---

select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000a', true);
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a","role":"authenticated"}', true);
set local role authenticated;

insert into public.collection_entry (media_id, media_type, title, production_status, added_at_epoch_ms, updated_at_epoch_ms)
values ('m1', 'MOVIE', 'first', 'RELEASED', 1, 1000);
insert into public.collection_entry (media_id, media_type, title, production_status, added_at_epoch_ms, updated_at_epoch_ms)
values ('m2', 'MOVIE', 'second', 'RELEASED', 1, 1000);

select ok(
  (select server_seq from public.collection_entry where media_id = 'm2') > (select server_seq from public.collection_entry where media_id = 'm1'),
  'a later insert takes a higher server_seq'
);

-- One sequence for every table, so a client's per-table cursors can never collide with another table's.
insert into public.episode_progress (episode_id, media_id, season_number, episode_number, seen, updated_at_epoch_ms)
values ('e1', 'm1', 1, 1, true, 1000);

select ok(
  (select server_seq from public.episode_progress where episode_id = 'e1') > (select server_seq from public.collection_entry where media_id = 'm2'),
  'the sequence is shared across tables'
);

-- A client cannot place a row behind someone else's cursor by sending its own server_seq.
insert into public.collection_entry (media_id, media_type, title, production_status, added_at_epoch_ms, updated_at_epoch_ms, server_seq)
values ('m3', 'MOVIE', 'spoofed', 'RELEASED', 1, 1000, 1);

select ok(
  (select server_seq from public.collection_entry where media_id = 'm3') > (select server_seq from public.collection_entry where media_id = 'm2'),
  'a client-supplied server_seq is overwritten'
);

-- -------------------------------------------------- last-write-wins + seq ---

-- The upsert the client sends: merge-duplicates is INSERT ... ON CONFLICT DO UPDATE.
create temp table seq_before as select server_seq as s from public.collection_entry where media_id = 'm1';

insert into public.collection_entry (media_id, media_type, title, production_status, added_at_epoch_ms, updated_at_epoch_ms)
values ('m1', 'MOVIE', 'newer', 'RELEASED', 1, 2000)
on conflict (user_id, media_id) do update
  set title = excluded.title, updated_at_epoch_ms = excluded.updated_at_epoch_ms;

select is((select title from public.collection_entry where media_id = 'm1'), 'newer', 'a newer write is applied');
select ok((select server_seq from public.collection_entry where media_id = 'm1') > (select s from seq_before), 'and takes a new server_seq');

create temp table seq_after_newer as select server_seq as s from public.collection_entry where media_id = 'm1';

insert into public.collection_entry (media_id, media_type, title, production_status, added_at_epoch_ms, updated_at_epoch_ms)
values ('m1', 'MOVIE', 'stale', 'RELEASED', 1, 1500)
on conflict (user_id, media_id) do update
  set title = excluded.title, updated_at_epoch_ms = excluded.updated_at_epoch_ms;

select is((select title from public.collection_entry where media_id = 'm1'), 'newer', 'a strictly older write is discarded');
select is(
  (select server_seq from public.collection_entry where media_id = 'm1'),
  (select s from seq_after_newer),
  'and a discarded write keeps its old server_seq, so it never appears in anyone''s feed'
);

insert into public.collection_entry (media_id, media_type, title, production_status, added_at_epoch_ms, updated_at_epoch_ms)
values ('m1', 'MOVIE', 'same stamp', 'RELEASED', 1, 2000)
on conflict (user_id, media_id) do update
  set title = excluded.title, updated_at_epoch_ms = excluded.updated_at_epoch_ms;

select is((select title from public.collection_entry where media_id = 'm1'), 'same stamp', 'an equal timestamp is accepted');
select ok((select server_seq from public.collection_entry where media_id = 'm1') > (select s from seq_after_newer), 'and takes a new server_seq');

-- ------------------------------------------------------------------ clamp ---

insert into public.collection_entry (media_id, media_type, title, production_status, added_at_epoch_ms, updated_at_epoch_ms)
values ('future', 'MOVIE', 'from a fast clock', 'RELEASED', 1, (extract(epoch from now()) * 1000)::bigint + 86400000);

select ok(
  (select updated_at_epoch_ms from public.collection_entry where media_id = 'future') <= (extract(epoch from clock_timestamp()) * 1000)::bigint + 61000,
  'a timestamp a day ahead is clamped to about a minute ahead'
);

insert into public.collection_entry (media_id, media_type, title, production_status, added_at_epoch_ms, updated_at_epoch_ms)
values ('past', 'MOVIE', 'ordinary', 'RELEASED', 1, 12345);

select is((select updated_at_epoch_ms from public.collection_entry where media_id = 'past'), 12345::bigint, 'a timestamp in the past is left alone');

-- -------------------------------------------------------------------- RLS ---

reset role;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000b', true);
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b","role":"authenticated"}', true);
set local role authenticated;

select is((select count(*)::int from public.collection_entry), 0, 'another user sees none of alice''s rows');

insert into public.collection_entry (media_id, media_type, title, production_status, added_at_epoch_ms, updated_at_epoch_ms)
values ('m1', 'MOVIE', 'bob''s own m1', 'RELEASED', 1, 500);

select is((select count(*)::int from public.collection_entry), 1, 'and can write the same key as a row of their own');

reset role;

select is(
  (select title from public.collection_entry where media_id = 'm1' and user_id = '00000000-0000-0000-0000-00000000000a'),
  'same stamp',
  'without touching alice''s row'
);

select * from finish();
rollback;
