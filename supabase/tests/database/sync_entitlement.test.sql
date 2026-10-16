-- pgTAP tests for EPIC 32 (#75, ADR 0019):
--   supabase/migrations/20261010000000_entitlement.sql
--   supabase/migrations/20261010000100_sync_requires_entitlement.sql
--
-- Run with `supabase test db` against the local stack (`supabase start`).
-- Everything runs inside one transaction that is rolled back.
begin;

select plan(46);

-- Users:
--   a  entitled, expires in 30 days
--   b  lapsed (active = false)
--   c  never paid (no row)
--   d  entitled with no end date (promo)
--   e  active but already past its expires_at
insert into auth.users (id, aud, role, email)
values
  ('00000000-0000-0000-0000-00000000000a', 'authenticated', 'authenticated', 'a@example.test'),
  ('00000000-0000-0000-0000-00000000000b', 'authenticated', 'authenticated', 'b@example.test'),
  ('00000000-0000-0000-0000-00000000000c', 'authenticated', 'authenticated', 'c@example.test'),
  ('00000000-0000-0000-0000-00000000000d', 'authenticated', 'authenticated', 'd@example.test'),
  ('00000000-0000-0000-0000-00000000000e', 'authenticated', 'authenticated', 'e@example.test');

insert into public.entitlement (user_id, active, expires_at, source_event, source_event_at)
values
  ('00000000-0000-0000-0000-00000000000a', true, date_trunc('second', now()) + interval '30 days', 'RENEWAL:1', now()),
  ('00000000-0000-0000-0000-00000000000b', false, now() - interval '1 day', 'EXPIRATION:2', now()),
  ('00000000-0000-0000-0000-00000000000d', true, null, 'INITIAL_PURCHASE:3', now()),
  ('00000000-0000-0000-0000-00000000000e', true, now() - interval '1 minute', 'RENEWAL:4', now());

-- Rows on the synced tables, written as postgres (bypassing RLS) so the tests
-- below only ask what each user can see and do.
insert into public.collection_entry (user_id, media_id, media_type, title, production_status, added_at_epoch_ms, updated_at_epoch_ms)
values
  ('00000000-0000-0000-0000-00000000000a', 'm1', 'MOVIE', 'a''s', 'RELEASED', 1, 1000),
  ('00000000-0000-0000-0000-00000000000b', 'm1', 'MOVIE', 'b''s', 'RELEASED', 1, 1000);

insert into public.cowatch_pool_entry (user_id, recipient_id, media_id, media_type, title, updated_at_epoch_ms)
values ('00000000-0000-0000-0000-00000000000d', '00000000-0000-0000-0000-00000000000a', 'm9', 'MOVIE', 'from d to a', 1000);

-- --------------------------------------------------------------- structure ---

select ok(
  (select relrowsecurity from pg_class where oid = 'public.entitlement'::regclass),
  'entitlement has RLS enabled'
);
select policies_are(
  'public', 'entitlement',
  array['own row readable', 'auth hook reads entitlements'],
  'entitlement has exactly a client read policy and the hook''s read policy — none that writes'
);
select ok(not has_table_privilege('anon', 'public.entitlement', 'select'), 'anon cannot select entitlement');
select ok(not has_table_privilege('anon', 'public.entitlement', 'insert'), 'anon cannot insert entitlement');
select ok(not has_table_privilege('authenticated', 'public.entitlement', 'insert'), 'authenticated cannot insert entitlement');
select ok(not has_table_privilege('authenticated', 'public.entitlement', 'update'), 'authenticated cannot update entitlement');
select ok(not has_table_privilege('authenticated', 'public.entitlement', 'delete'), 'authenticated cannot delete entitlement');

select ok(
  has_function_privilege('supabase_auth_admin', 'public.custom_access_token_hook(jsonb)', 'execute'),
  'supabase_auth_admin may execute the hook'
);
select ok(
  not has_function_privilege('authenticated', 'public.custom_access_token_hook(jsonb)', 'execute')
    and not has_function_privilege('anon', 'public.custom_access_token_hook(jsonb)', 'execute'),
  'clients may not call the hook'
);

-- Every synced table: the claim is in both halves of the policy, and the
-- policy is flat (ADR 0022 invariant 4: no subquery).
select is(
  (select count(*)::int from pg_policies
    where schemaname = 'public'
      and tablename in ('collection_entry', 'episode_progress', 'media_list', 'list_entry', 'triage_decision',
                        'episode_play', 'triage_snooze', 'cowatch_link', 'cowatch_pool_entry')
      and qual like '%sync_until%' and with_check like '%sync_until%'),
  9,
  'all nine synced tables require sync_until for reads and writes'
);
select is(
  (select count(*)::int from pg_policies
    where schemaname = 'public'
      and tablename in ('collection_entry', 'episode_progress', 'media_list', 'list_entry', 'triage_decision',
                        'episode_play', 'triage_snooze', 'cowatch_link', 'cowatch_pool_entry')),
  9,
  'and each has exactly one policy, so no older permissive one is left behind'
);
select is(
  (select count(*)::int from pg_policies
    where schemaname = 'public' and (qual ilike '%select%' or coalesce(with_check, '') ilike '%select%')),
  0,
  'no public policy contains a subquery'
);
select is(
  (select count(*)::int from information_schema.role_table_grants
    where table_schema = 'public' and grantee = 'anon'),
  0,
  'anon holds no privilege on any public table'
);

-- -------------------------------------------------------------------- hook ---
-- Called as postgres: the test runner may not `set role supabase_auth_admin`,
-- which is the role GoTrue really calls it as. What that role needs is
-- asserted structurally here; the GoTrue path itself (sign in, decode the
-- token) is the manual check in docs/SYNC.md "Entitlement".

select ok(has_table_privilege('supabase_auth_admin', 'public.entitlement', 'select'), 'supabase_auth_admin may select entitlement');
select is(
  (select qual from pg_policies where schemaname = 'public' and tablename = 'entitlement' and roles = '{supabase_auth_admin}' and cmd = 'SELECT'),
  'true',
  'and RLS lets it read every row (it does not bypass RLS)'
);
select set_config('test.hook_a', public.custom_access_token_hook(
  '{"user_id":"00000000-0000-0000-0000-00000000000a","authentication_method":"oauth","claims":{"sub":"00000000-0000-0000-0000-00000000000a","role":"authenticated","aud":"authenticated"}}'
)::text, true);
select set_config('test.hook_b', public.custom_access_token_hook(
  '{"user_id":"00000000-0000-0000-0000-00000000000b","authentication_method":"token_refresh","claims":{"sub":"00000000-0000-0000-0000-00000000000b","role":"authenticated","sync_until":"9999-12-31T23:59:59Z"}}'
)::text, true);
select set_config('test.hook_c', public.custom_access_token_hook(
  '{"user_id":"00000000-0000-0000-0000-00000000000c","authentication_method":"oauth","claims":{"sub":"00000000-0000-0000-0000-00000000000c","role":"authenticated"}}'
)::text, true);
select set_config('test.hook_d', public.custom_access_token_hook(
  '{"user_id":"00000000-0000-0000-0000-00000000000d","authentication_method":"oauth","claims":{"sub":"00000000-0000-0000-0000-00000000000d","role":"authenticated"}}'
)::text, true);
select set_config('test.hook_e', public.custom_access_token_hook(
  '{"user_id":"00000000-0000-0000-0000-00000000000e","authentication_method":"oauth","claims":{"sub":"00000000-0000-0000-0000-00000000000e","role":"authenticated"}}'
)::text, true);

select is(
  current_setting('test.hook_a')::jsonb->'claims'->>'sync_until',
  to_char((select expires_at from public.entitlement where user_id = '00000000-0000-0000-0000-00000000000a') at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
  'an active grant emits sync_until = expires_at, as an ISO-8601 UTC instant'
);
select ok(
  (current_setting('test.hook_a')::jsonb->'claims'->>'sync_until')::timestamptz > now(),
  'which the policies'' cast reads as a future timestamptz'
);
select is(current_setting('test.hook_a')::jsonb->'claims'->>'sub', '00000000-0000-0000-0000-00000000000a', 'the hook keeps the other claims');
select is(current_setting('test.hook_a')::jsonb->>'user_id', '00000000-0000-0000-0000-00000000000a', 'and returns the rest of the event');
select ok(not (current_setting('test.hook_b')::jsonb->'claims' ? 'sync_until'), 'an inactive grant omits sync_until, even one sent in');
select ok(not (current_setting('test.hook_c')::jsonb->'claims' ? 'sync_until'), 'no entitlement row omits sync_until');
select is(current_setting('test.hook_d')::jsonb->'claims'->>'sync_until', '9999-12-31T23:59:59Z', 'an active grant with no end renders far in the future');
select ok(not (current_setting('test.hook_e')::jsonb->'claims' ? 'sync_until'), 'an active grant already past its end omits sync_until');

-- ------------------------------------------------- client and entitlement ---

select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a","role":"authenticated","sync_until":"9999-12-31T23:59:59Z"}', true);
set local role authenticated;

select is((select count(*)::int from public.entitlement), 1, 'a client sees its own entitlement row and no one else''s');
select throws_ok(
  $$insert into public.entitlement (user_id, active) values ('00000000-0000-0000-0000-00000000000c', true)$$,
  '42501', null, 'a client cannot grant an entitlement'
);
select throws_ok(
  $$update public.entitlement set expires_at = null where user_id = '00000000-0000-0000-0000-00000000000a'$$,
  '42501', null, 'a client cannot extend its own'
);
select throws_ok(
  $$delete from public.entitlement where user_id = '00000000-0000-0000-0000-00000000000a'$$,
  '42501', null, 'a client cannot delete its own'
);
reset role;

-- A lapsed user still reads their own row (that is how the client learns it
-- lapsed), with no claim at all.
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b","role":"authenticated"}', true);
set local role authenticated;
select is((select active from public.entitlement), false, 'a lapsed client still reads its own entitlement');
reset role;

-- --------------------------------------------- synced rows need the claim ---

-- b: no claim at all.
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b","role":"authenticated"}', true);
set local role authenticated;

select is((select count(*)::int from public.collection_entry), 0, 'without sync_until a user cannot read their own rows');
select throws_ok(
  $$insert into public.collection_entry (media_id, media_type, title, production_status, added_at_epoch_ms, updated_at_epoch_ms)
    values ('m2', 'MOVIE', 'new', 'RELEASED', 1, 2000)$$,
  '42501', null, 'nor insert one'
);
select throws_ok(
  $$insert into public.collection_entry (media_id, media_type, title, production_status, added_at_epoch_ms, updated_at_epoch_ms)
    values ('m1', 'MOVIE', 'overwrite', 'RELEASED', 1, 2000)
    on conflict (user_id, media_id) do update set title = excluded.title, updated_at_epoch_ms = excluded.updated_at_epoch_ms$$,
  '42501', null, 'nor upsert over an existing one (the push path)'
);
update public.collection_entry set title = 'patched' where media_id = 'm1';
select throws_ok(
  $$insert into public.episode_progress (episode_id, media_id, season_number, episode_number, seen, updated_at_epoch_ms)
    values ('e1', 'm1', 1, 1, true, 1000)$$,
  '42501', null, 'episode_progress refuses the write too'
);
select throws_ok(
  $$insert into public.triage_snooze (media_id, media_type, title, snoozed_at_epoch_ms, due_at_epoch_day, updated_at_epoch_ms)
    values ('m3', 'MOVIE', 't', 1, 1, 1)$$,
  '42501', null, 'and so does triage_snooze'
);
reset role;

select is(
  (select title from public.collection_entry where user_id = '00000000-0000-0000-0000-00000000000b' and media_id = 'm1'),
  'b''s',
  'a plain UPDATE without the claim matched nothing and changed nothing'
);

-- b: an expired claim.
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b","role":"authenticated","sync_until":"2020-01-01T00:00:00Z"}', true);
set local role authenticated;
select is((select count(*)::int from public.collection_entry), 0, 'an expired sync_until cannot read');
select throws_ok(
  $$insert into public.collection_entry (media_id, media_type, title, production_status, added_at_epoch_ms, updated_at_epoch_ms)
    values ('m2', 'MOVIE', 'new', 'RELEASED', 1, 2000)$$,
  '42501', null, 'nor write'
);
reset role;

-- b: a valid claim — the rows were kept through the lapse, read-locked, not purged.
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b","role":"authenticated","sync_until":"9999-12-31T23:59:59Z"}', true);
set local role authenticated;
select is((select title from public.collection_entry where media_id = 'm1'), 'b''s', 'with a valid sync_until the kept rows are readable again');
select lives_ok(
  $$insert into public.collection_entry (media_id, media_type, title, production_status, added_at_epoch_ms, updated_at_epoch_ms)
    values ('m1', 'MOVIE', 'renewed', 'RELEASED', 1, 2000)
    on conflict (user_id, media_id) do update set title = excluded.title, updated_at_epoch_ms = excluded.updated_at_epoch_ms$$,
  'and writable'
);
select ok(
  (select server_seq from public.collection_entry where media_id = 'm1') is not null
    and (select title from public.collection_entry where media_id = 'm1') = 'renewed',
  'the last-write-wins trigger still stamps the accepted write'
);
select is((select count(*)::int from public.collection_entry), 1, 'and still only their own rows');
reset role;

-- Co-watch: the addressee needs a claim of their own to read.
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a","role":"authenticated","sync_until":"9999-12-31T23:59:59Z"}', true);
set local role authenticated;
select is((select count(*)::int from public.cowatch_pool_entry), 1, 'an entitled addressee reads a pool addressed to them');
reset role;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a","role":"authenticated"}', true);
set local role authenticated;
select is((select count(*)::int from public.cowatch_pool_entry), 0, 'an unentitled addressee does not');
reset role;

-- ------------------------------------------------ webhook delivery order ---

insert into public.entitlement (user_id, active, expires_at, source_event, source_event_at)
values ('00000000-0000-0000-0000-00000000000a', false, now(), 'EXPIRATION:old', now() - interval '1 hour')
on conflict (user_id) do update set active = excluded.active, expires_at = excluded.expires_at,
  source_event = excluded.source_event, source_event_at = excluded.source_event_at;
select is(
  (select source_event from public.entitlement where user_id = '00000000-0000-0000-0000-00000000000a'),
  'RENEWAL:1',
  'an event older than the applied one is discarded'
);

-- ---------------------------------------------------------------- cascade ---

delete from auth.users where id = '00000000-0000-0000-0000-00000000000a';
select is((select count(*)::int from public.entitlement where user_id = '00000000-0000-0000-0000-00000000000a'), 0, 'deleting the auth user removes their entitlement');
select is((select count(*)::int from public.collection_entry where user_id = '00000000-0000-0000-0000-00000000000a'), 0, 'their synced rows');
select is((select count(*)::int from public.cowatch_pool_entry where recipient_id = '00000000-0000-0000-0000-00000000000a'), 0, 'and rows addressed to them');

select * from finish();
rollback;
