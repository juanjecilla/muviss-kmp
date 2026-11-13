-- pgTAP: Row Level Security across every table (#129). Run with
-- `supabase test db`; CI's `supabase` lane runs it on any supabase/ change.
--
-- RLS is the whole access-control story: the anon key ships in the client and
-- there is no app server. scripts/sync/verify-rls.sh probes a hosted project
-- from outside; this checks the same claims, plus the ones that need several
-- real accounts, against the migrations themselves on every PR.
--
-- Everything runs inside one transaction that is rolled back.
begin;

select plan(25);

-- ------------------------------------------------------------- structure ---

-- A table added without `enable row level security` is readable by anyone
-- holding the anon key, i.e. anyone who installs the app.
select is(
  (select array_agg(c.relname::text order by c.relname)
     from pg_class c join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'public' and c.relkind in ('r', 'p') and not c.relrowsecurity),
  null,
  'every table in public has row level security enabled'
);

select is(
  (select array_agg(c.relname::text order by c.relname)
     from pg_class c join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'public' and c.relkind in ('r', 'p')
      and not exists (select 1 from pg_policies p where p.schemaname = 'public' and p.tablename = c.relname)),
  null,
  'and at least one policy (RLS with none refuses everything, which is a broken table, not a safe one)'
);

-- discard_stale_write() also assigns server_seq; a table without it never
-- appears in anyone's pull.
select has_trigger('public', 'cowatch_link', 'cowatch_link_lww', 'cowatch_link has the LWW / server_seq trigger');
select has_trigger('public', 'cowatch_pool_entry', 'cowatch_pool_entry_lww', 'cowatch_pool_entry has it too');
select has_trigger('public', 'triage_snooze', 'triage_snooze_lww', 'and triage_snooze');

-- --------------------------------------------------------------- accounts ---

-- A and B are Companions; C is a third account linked to neither.
insert into auth.users (id, aud, role, email)
values
  ('00000000-0000-0000-0000-0000000000a1', 'authenticated', 'authenticated', 'a@example.test'),
  ('00000000-0000-0000-0000-0000000000b1', 'authenticated', 'authenticated', 'b@example.test'),
  ('00000000-0000-0000-0000-0000000000c1', 'authenticated', 'authenticated', 'c@example.test');

-- Acting as a user: the claims auth.uid() reads, then the `authenticated` role
-- so RLS applies. `reset role` before switching. The `sync_until` claim is what
-- the entitlement gate's policies (#283) also require; it is inert until then,
-- and keeps these assertions about *who* may read, not about entitlement.
create function pg_temp.act_as(uid text) returns void language plpgsql as $$
begin
  perform set_config('request.jwt.claim.sub', uid, true);
  perform set_config('request.jwt.claims',
    json_build_object('sub', uid, 'role', 'authenticated', 'sync_until', '2999-01-01T00:00:00Z')::text, true);
end $$;

-- A: a link to B, a pool entry for B, a snooze of its own.
select pg_temp.act_as('00000000-0000-0000-0000-0000000000a1');
set local role authenticated;
insert into public.cowatch_link (recipient_id, state, nonce, created_at_epoch_ms, updated_at_epoch_ms)
values ('00000000-0000-0000-0000-0000000000b1', 'LINKED', 'n-a', 1, 1000);
insert into public.cowatch_pool_entry (recipient_id, media_id, media_type, title, updated_at_epoch_ms)
values ('00000000-0000-0000-0000-0000000000b1', 'tmdb:movie:1', 'MOVIE', 'from A', 1000);
insert into public.triage_snooze (media_id, media_type, title, snoozed_at_epoch_ms, due_at_epoch_day, updated_at_epoch_ms)
values ('tmdb:movie:9', 'MOVIE', 'A snoozed', 1, 1, 1000);
reset role;

-- B: the other side of the link, and a pool entry for A.
select pg_temp.act_as('00000000-0000-0000-0000-0000000000b1');
set local role authenticated;
insert into public.cowatch_link (recipient_id, state, nonce, created_at_epoch_ms, updated_at_epoch_ms)
values ('00000000-0000-0000-0000-0000000000a1', 'LINKED', 'n-b', 1, 1000);
insert into public.cowatch_pool_entry (recipient_id, media_id, media_type, title, updated_at_epoch_ms)
values ('00000000-0000-0000-0000-0000000000a1', 'tmdb:movie:2', 'MOVIE', 'from B', 1000);
reset role;

select ok(
  (select bool_and(server_seq is not null) from public.cowatch_pool_entry),
  'co-watch rows are given a server_seq'
);

-- ------------------------------------------------------- addressed reads ---

select pg_temp.act_as('00000000-0000-0000-0000-0000000000b1');
set local role authenticated;
select is((select count(*)::int from public.cowatch_pool_entry), 2, 'B reads its own pool entry and the one A addressed to it');
select is((select count(*)::int from public.cowatch_link), 2, 'B reads both halves of its link');
select is((select count(*)::int from public.triage_snooze), 0, 'B cannot read A''s snoozes');
reset role;

select pg_temp.act_as('00000000-0000-0000-0000-0000000000c1');
set local role authenticated;
select is((select count(*)::int from public.cowatch_pool_entry), 0, 'C, linked to nobody, reads no pool entries');
select is((select count(*)::int from public.cowatch_link), 0, 'C reads no links');
select is((select count(*)::int from public.triage_snooze), 0, 'C reads no snoozes');

-- ------------------------------------------------------- forged writes ---

select throws_ok(
  $$insert into public.cowatch_pool_entry (user_id, recipient_id, media_id, media_type, title, updated_at_epoch_ms)
    values ('00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-0000000000c1', 'tmdb:movie:3', 'MOVIE', 'forged', 1000)$$,
  '42501', null,
  'C cannot write a pool entry posing as A (which would hand C a read of it)'
);
select throws_ok(
  $$insert into public.cowatch_link (user_id, recipient_id, state, nonce, created_at_epoch_ms, updated_at_epoch_ms)
    values ('00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-0000000000c1', 'LINKED', 'x', 1, 1000)$$,
  '42501', null,
  'C cannot write a link posing as A'
);
select throws_ok(
  $$insert into public.triage_snooze (user_id, media_id, media_type, title, snoozed_at_epoch_ms, due_at_epoch_day, updated_at_epoch_ms)
    values ('00000000-0000-0000-0000-0000000000a1', 'tmdb:movie:4', 'MOVIE', 'forged', 1, 1, 1000)$$,
  '42501', null,
  'C cannot write a snooze into A''s library'
);
reset role;

-- ------------------------------------------- the recipient only reads ---

-- "Two rows per relation, one per side, each owned by its author: neither
-- side can write the other's" (20260921000000_cowatch.sql). The addressee may
-- read the author's row; it may not change or remove it. A refused UPDATE or
-- DELETE on a row the policy hides is not an error, just zero rows, so the
-- assertions read the rows back as their author afterwards.
select pg_temp.act_as('00000000-0000-0000-0000-0000000000b1');
set local role authenticated;
update public.cowatch_pool_entry set title = 'edited by B', updated_at_epoch_ms = 2000
 where user_id = '00000000-0000-0000-0000-0000000000a1';
update public.cowatch_link set state = 'EDITED', updated_at_epoch_ms = 2000
 where user_id = '00000000-0000-0000-0000-0000000000a1';
delete from public.cowatch_pool_entry where user_id = '00000000-0000-0000-0000-0000000000a1';
delete from public.cowatch_link where user_id = '00000000-0000-0000-0000-0000000000a1';
reset role;

select is(
  (select title from public.cowatch_pool_entry where user_id = '00000000-0000-0000-0000-0000000000a1'),
  'from A',
  'B cannot edit the pool entry A addressed to it'
);
select is(
  (select state from public.cowatch_link where user_id = '00000000-0000-0000-0000-0000000000a1'),
  'LINKED',
  'B cannot edit A''s half of the link'
);

select is(
  (select count(*)::int from public.cowatch_pool_entry where user_id = '00000000-0000-0000-0000-0000000000a1'),
  1,
  'B cannot delete the pool entry A addressed to it'
);
select is(
  (select count(*)::int from public.cowatch_link where user_id = '00000000-0000-0000-0000-0000000000a1'),
  1,
  'B cannot delete A''s half of the link'
);

-- ------------------------------------------------------------ anonymous ---

-- The anon key with no session. Since the entitlement migration anon holds no
-- table privilege at all (20261022000100), so a read is refused outright with
-- 42501 rather than answered with an empty set; RLS would have matched nothing
-- either way, now it is never reached.
select set_config('request.jwt.claim.sub', '', true);
select set_config('request.jwt.claims', '{"role":"anon"}', true);
set local role anon;

select throws_ok($$select count(*) from public.collection_entry$$, '42501', null, 'anon cannot read collection entries');
select throws_ok($$select count(*) from public.cowatch_pool_entry$$, '42501', null, 'anon cannot read pool entries');
select throws_ok($$select count(*) from public.cowatch_link$$, '42501', null, 'anon cannot read links');
select throws_ok($$select count(*) from public.triage_snooze$$, '42501', null, 'anon cannot read snoozes');

select throws_ok(
  $$insert into public.cowatch_pool_entry (user_id, recipient_id, media_id, media_type, title, updated_at_epoch_ms)
    values ('00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-0000000000b1', 'tmdb:movie:5', 'MOVIE', 'anon', 1000)$$,
  '42501', null,
  'anon cannot write a pool entry'
);
select throws_ok(
  $$insert into public.triage_snooze (user_id, media_id, media_type, title, snoozed_at_epoch_ms, due_at_epoch_day, updated_at_epoch_ms)
    values ('00000000-0000-0000-0000-0000000000a1', 'tmdb:movie:6', 'MOVIE', 'anon', 1, 1, 1000)$$,
  '42501', null,
  'anon cannot write a snooze'
);
reset role;

select * from finish();
rollback;
