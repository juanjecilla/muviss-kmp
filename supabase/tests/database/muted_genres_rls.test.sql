-- pgTAP: the Muted Genres marker and decisions (ADR 0036, #644). Run with `supabase test db`, which CI runs on any
-- supabase/ change against the migrations from empty. One transaction, rolled back, as sync_rls.test.sql does.
begin;

select plan(13);

select has_trigger('public', 'muted_genre', 'muted_genre_lww', 'muted_genre has the LWW / server_seq trigger');
select has_trigger('public', 'muted_genre_choice', 'muted_genre_choice_lww', 'muted_genre_choice has it too');

insert into auth.users (id, aud, role, email)
values
  ('00000000-0000-0000-0000-0000000000a3', 'authenticated', 'authenticated', 'a3@example.test'),
  ('00000000-0000-0000-0000-0000000000b3', 'authenticated', 'authenticated', 'b3@example.test');

create function pg_temp.act_as(uid text) returns void language plpgsql as $$
begin
  perform set_config('request.jwt.claim.sub', uid, true);
  perform set_config('request.jwt.claims',
    json_build_object('sub', uid, 'role', 'authenticated', 'sync_until', '2999-01-01T00:00:00Z')::text, true);
end $$;

-- A writes its marker, a live mute of a genre that was not a default, and a tombstone that unmutes a default.
select pg_temp.act_as('00000000-0000-0000-0000-0000000000a3');
set local role authenticated;
insert into public.muted_genre_choice (defaults_at_choice, updated_at_epoch_ms)
values ('tv:10767:Talk;tv:10763:News;tv:10764:Reality', 1000);
insert into public.muted_genre (media_type, genre_id, name, deleted, updated_at_epoch_ms)
values ('movie', 27, 'Horror', false, 1000),
       ('tv', 10767, 'Talk', true, 1000);
reset role;

select ok(
  (select bool_and(server_seq is not null) from public.muted_genre),
  'the decisions are given a server_seq, so the pull can page to them'
);

select is(
  (select deleted from public.muted_genre where genre_id = 10767 and user_id = '00000000-0000-0000-0000-0000000000a3'),
  true,
  'a tombstone is stored as a tombstone, not dropped, so an unmute survives a sync'
);

-- B reads none of A's marker or decisions, and cannot write under A's id.
select pg_temp.act_as('00000000-0000-0000-0000-0000000000b3');
set local role authenticated;
select is((select count(*)::int from public.muted_genre), 0, 'B reads no decisions but its own');
select is((select count(*)::int from public.muted_genre_choice), 0, 'B reads no marker but its own');
select throws_ok(
  $$insert into public.muted_genre (user_id, media_type, genre_id, name, deleted, updated_at_epoch_ms)
    values ('00000000-0000-0000-0000-0000000000a3', 'tv', 10763, 'News', false, 2000)$$,
  '42501', null,
  'B cannot write a decision posing as A'
);
update public.muted_genre set deleted = false, updated_at_epoch_ms = 2000
 where user_id = '00000000-0000-0000-0000-0000000000a3';
reset role;

select is(
  (select deleted from public.muted_genre where genre_id = 10767 and user_id = '00000000-0000-0000-0000-0000000000a3'),
  true,
  'B cannot edit A''s decision (its update matched nothing)'
);

-- A stale write from A's other device is discarded and keeps the newer tombstone.
select pg_temp.act_as('00000000-0000-0000-0000-0000000000a3');
set local role authenticated;
update public.muted_genre set deleted = false, updated_at_epoch_ms = 500 where genre_id = 10767;
reset role;

select is(
  (select deleted from public.muted_genre where genre_id = 10767 and user_id = '00000000-0000-0000-0000-0000000000a3'),
  true,
  'an older write to a decision is discarded'
);

-- Anonymous: no read and no write.
select set_config('request.jwt.claim.sub', '', true);
select set_config('request.jwt.claims', '{"role":"anon"}', true);
set local role anon;
select is((select count(*)::int from public.muted_genre), 0, 'anon reads no decisions');
select is((select count(*)::int from public.muted_genre_choice), 0, 'anon reads no marker');
select throws_ok(
  $$insert into public.muted_genre_choice (user_id, defaults_at_choice, updated_at_epoch_ms)
    values ('00000000-0000-0000-0000-0000000000b3', 'x', 1000)$$,
  '42501', null,
  'anon cannot write a marker'
);
reset role;

select ok(
  (select relrowsecurity from pg_class where oid = 'public.muted_genre'::regclass)
    and (select relrowsecurity from pg_class where oid = 'public.muted_genre_choice'::regclass),
  'row level security is on for both tables'
);

select * from finish();
rollback;
