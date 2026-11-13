-- pgTAP: the Display Preferences group (ADR 0036, #643). Run with `supabase test db`, which CI runs on
-- any supabase/ change against the migrations from empty. Everything runs inside one transaction that is
-- rolled back, as sync_rls.test.sql does.
begin;

select plan(13);

select has_trigger('public', 'display_preferences', 'display_preferences_lww', 'display_preferences has the LWW / server_seq trigger');

-- Two accounts. A owns a group; B is a second account that must see none of it.
insert into auth.users (id, aud, role, email)
values
  ('00000000-0000-0000-0000-0000000000a2', 'authenticated', 'authenticated', 'a2@example.test'),
  ('00000000-0000-0000-0000-0000000000b2', 'authenticated', 'authenticated', 'b2@example.test');

create function pg_temp.act_as(uid text) returns void language plpgsql as $$
begin
  perform set_config('request.jwt.claim.sub', uid, true);
  perform set_config('request.jwt.claims',
    json_build_object('sub', uid, 'role', 'authenticated', 'sync_until', '2999-01-01T00:00:00Z')::text, true);
end $$;

-- A writes its group. user_id comes from auth.uid() by default, as the client's requests rely on.
select pg_temp.act_as('00000000-0000-0000-0000-0000000000a2');
set local role authenticated;
insert into public.display_preferences (theme, language, region, animations_enabled, triage_deck_animations,
  tick_animations, triage_control_scheme, triage_snooze_period, triage_snooze_placement, muted_genres_in_search,
  updated_at_epoch_ms)
values ('DARK', 'fr', 'FR', true, true, true, 'FOUR_WAY', 'ONE_WEEK', 'MIXED_IN', false, 1000);
reset role;

select is(
  (select user_id from public.display_preferences),
  '00000000-0000-0000-0000-0000000000a2'::uuid,
  'the group is stored under the authenticated account, without the client sending user_id'
);

select ok(
  (select server_seq is not null from public.display_preferences),
  'the insert is given a server_seq, so the pull can page to it'
);

-- B reads nothing of A's, and cannot write under A's id.
select pg_temp.act_as('00000000-0000-0000-0000-0000000000b2');
set local role authenticated;
select is((select count(*)::int from public.display_preferences), 0, 'B reads no group but its own');
select throws_ok(
  $$insert into public.display_preferences (user_id, theme, language, region, animations_enabled, triage_deck_animations,
      tick_animations, triage_control_scheme, triage_snooze_period, triage_snooze_placement, muted_genres_in_search,
      updated_at_epoch_ms)
    values ('00000000-0000-0000-0000-0000000000a2', 'LIGHT', '', 'US', true, true, true, 'FOUR_WAY', 'ONE_WEEK',
      'MIXED_IN', false, 2000)$$,
  '42501', null,
  'B cannot write a group posing as A'
);
update public.display_preferences set theme = 'LIGHT', updated_at_epoch_ms = 2000
 where user_id = '00000000-0000-0000-0000-0000000000a2';
reset role;

select is(
  (select theme from public.display_preferences where user_id = '00000000-0000-0000-0000-0000000000a2'),
  'DARK',
  'B cannot edit A''s group (its update matched nothing)'
);

-- A stale write from A's own other device is discarded and keeps the old row and its server_seq.
select pg_temp.act_as('00000000-0000-0000-0000-0000000000a2');
set local role authenticated;
update public.display_preferences set theme = 'LIGHT', updated_at_epoch_ms = 500;
reset role;

select is(
  (select theme from public.display_preferences where user_id = '00000000-0000-0000-0000-0000000000a2'),
  'DARK',
  'an older write is discarded: the stored group keeps its newer stamp''s values'
);

-- A newer write applies, and moves the sequence.
select pg_temp.act_as('00000000-0000-0000-0000-0000000000a2');
set local role authenticated;
update public.display_preferences set theme = 'LIGHT', updated_at_epoch_ms = 3000;
reset role;

select is(
  (select theme from public.display_preferences where user_id = '00000000-0000-0000-0000-0000000000a2'),
  'LIGHT',
  'a newer write applies'
);

-- Anonymous: no session, so no read and no write.
select set_config('request.jwt.claim.sub', '', true);
select set_config('request.jwt.claims', '{"role":"anon"}', true);
set local role anon;
select is((select count(*)::int from public.display_preferences), 0, 'anon reads no group');
select throws_ok(
  $$insert into public.display_preferences (user_id, theme, language, region, animations_enabled, triage_deck_animations,
      tick_animations, triage_control_scheme, triage_snooze_period, triage_snooze_placement, muted_genres_in_search,
      updated_at_epoch_ms)
    values ('00000000-0000-0000-0000-0000000000b2', 'LIGHT', '', 'US', true, true, true, 'FOUR_WAY', 'ONE_WEEK',
      'MIXED_IN', false, 1000)$$,
  '42501', null,
  'anon cannot write a group'
);
reset role;

-- The pull pages by server_seq within the account, the index the client's query needs.
select ok(
  exists (
    select 1 from pg_indexes
     where schemaname = 'public' and tablename = 'display_preferences' and indexdef like '%(user_id, server_seq)%'
  ),
  'the (user_id, server_seq) paging index exists'
);

select ok(
  (select relrowsecurity from pg_class where oid = 'public.display_preferences'::regclass),
  'row level security is on'
);

select is(
  (select count(*)::int from public.display_preferences where user_id is null),
  0,
  'every group is owned by an account'
);

select * from finish();
rollback;
