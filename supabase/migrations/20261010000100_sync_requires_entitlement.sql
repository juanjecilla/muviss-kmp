-- EPIC 32 (#75, ADR 0019): every synced table now also requires a paid
-- entitlement, enforced by the server rather than only by `SyncEngine`.
--
-- The client-side gate (`EntitlementGate` inside `SyncEngine.syncNow`, ADR
-- 0018) keeps an honest client honest; it cannot stop a patched one, and the
-- anon key ships in every binary. So each policy below gains one more flat
-- comparison, against the `sync_until` claim the custom access token hook
-- stamps into the JWT (previous migration):
--
--     (auth.jwt()->>'sync_until')::timestamptz > now()
--
-- No subquery and no `security definer`: ADR 0022's invariant 4 holds. The
-- hook is the only place that joins `entitlement`, once per token.
--
-- Fails closed. A token without the claim — never paid, lapsed, or minted
-- before this migration — casts to null, `null > now()` is null, and RLS
-- treats null as false: reads return `200 []`, writes are refused with
-- 42501 (PostgREST: HTTP 403). The lag this buys is bounded by the token's
-- lifetime (`jwt_expiry`, an hour): a lapse takes effect when the current
-- token expires or `sync_until` passes, whichever is first.
--
-- A lapsed account's rows are KEPT and read-locked, not purged (ADR 0019):
-- renewing resumes where it stopped.
--
-- Both directions of co-watch need the claim: the author's to publish, the
-- addressee's to read (ADR 0022, "Both sides must be entitled").
--
-- Policies are now `to authenticated`: anon never had a reason to reach these
-- tables, and its table privileges are revoked below too. The triggers
-- (`discard_stale_write()`, which clamps, discards stale writes and stamps
-- `server_seq`) are untouched; they run before RLS's WITH CHECK, as before.
--
-- Table privileges are made explicit because `auto_expose_new_tables = false`
-- (config.toml) stops Supabase granting them by default. On a project that
-- already has the defaults this narrows them: anon loses everything,
-- authenticated keeps exactly select/insert/update/delete (losing TRUNCATE,
-- which RLS does not govern).

-- collection_entry --
drop policy if exists "own rows only" on public.collection_entry;
drop policy if exists "own rows while entitled" on public.collection_entry;
create policy "own rows while entitled" on public.collection_entry
  for all to authenticated
  using (auth.uid() = user_id and (auth.jwt()->>'sync_until')::timestamptz > now())
  with check (auth.uid() = user_id and (auth.jwt()->>'sync_until')::timestamptz > now());
revoke all on table public.collection_entry from anon, authenticated, public;
grant select, insert, update, delete on table public.collection_entry to authenticated;
grant all on table public.collection_entry to service_role;

-- episode_progress --
drop policy if exists "own rows only" on public.episode_progress;
drop policy if exists "own rows while entitled" on public.episode_progress;
create policy "own rows while entitled" on public.episode_progress
  for all to authenticated
  using (auth.uid() = user_id and (auth.jwt()->>'sync_until')::timestamptz > now())
  with check (auth.uid() = user_id and (auth.jwt()->>'sync_until')::timestamptz > now());
revoke all on table public.episode_progress from anon, authenticated, public;
grant select, insert, update, delete on table public.episode_progress to authenticated;
grant all on table public.episode_progress to service_role;

-- media_list --
drop policy if exists "own rows only" on public.media_list;
drop policy if exists "own rows while entitled" on public.media_list;
create policy "own rows while entitled" on public.media_list
  for all to authenticated
  using (auth.uid() = user_id and (auth.jwt()->>'sync_until')::timestamptz > now())
  with check (auth.uid() = user_id and (auth.jwt()->>'sync_until')::timestamptz > now());
revoke all on table public.media_list from anon, authenticated, public;
grant select, insert, update, delete on table public.media_list to authenticated;
grant all on table public.media_list to service_role;

-- list_entry --
drop policy if exists "own rows only" on public.list_entry;
drop policy if exists "own rows while entitled" on public.list_entry;
create policy "own rows while entitled" on public.list_entry
  for all to authenticated
  using (auth.uid() = user_id and (auth.jwt()->>'sync_until')::timestamptz > now())
  with check (auth.uid() = user_id and (auth.jwt()->>'sync_until')::timestamptz > now());
revoke all on table public.list_entry from anon, authenticated, public;
grant select, insert, update, delete on table public.list_entry to authenticated;
grant all on table public.list_entry to service_role;

-- triage_decision --
drop policy if exists "own rows only" on public.triage_decision;
drop policy if exists "own rows while entitled" on public.triage_decision;
create policy "own rows while entitled" on public.triage_decision
  for all to authenticated
  using (auth.uid() = user_id and (auth.jwt()->>'sync_until')::timestamptz > now())
  with check (auth.uid() = user_id and (auth.jwt()->>'sync_until')::timestamptz > now());
revoke all on table public.triage_decision from anon, authenticated, public;
grant select, insert, update, delete on table public.triage_decision to authenticated;
grant all on table public.triage_decision to service_role;

-- episode_play --
drop policy if exists "own rows only" on public.episode_play;
drop policy if exists "own rows while entitled" on public.episode_play;
create policy "own rows while entitled" on public.episode_play
  for all to authenticated
  using (auth.uid() = user_id and (auth.jwt()->>'sync_until')::timestamptz > now())
  with check (auth.uid() = user_id and (auth.jwt()->>'sync_until')::timestamptz > now());
revoke all on table public.episode_play from anon, authenticated, public;
grant select, insert, update, delete on table public.episode_play to authenticated;
grant all on table public.episode_play to service_role;

-- triage_snooze --
drop policy if exists "own rows only" on public.triage_snooze;
drop policy if exists "own rows while entitled" on public.triage_snooze;
create policy "own rows while entitled" on public.triage_snooze
  for all to authenticated
  using (auth.uid() = user_id and (auth.jwt()->>'sync_until')::timestamptz > now())
  with check (auth.uid() = user_id and (auth.jwt()->>'sync_until')::timestamptz > now());
revoke all on table public.triage_snooze from anon, authenticated, public;
grant select, insert, update, delete on table public.triage_snooze to authenticated;
grant all on table public.triage_snooze to service_role;

-- cowatch_link -- (addressed rows, ADR 0022)
drop policy if exists "author and addressee" on public.cowatch_link;
drop policy if exists "author and addressee while entitled" on public.cowatch_link;
create policy "author and addressee while entitled" on public.cowatch_link
  for all to authenticated
  using ((auth.uid() = user_id or auth.uid() = recipient_id) and (auth.jwt()->>'sync_until')::timestamptz > now())
  with check (auth.uid() = user_id and (auth.jwt()->>'sync_until')::timestamptz > now());
revoke all on table public.cowatch_link from anon, authenticated, public;
grant select, insert, update, delete on table public.cowatch_link to authenticated;
grant all on table public.cowatch_link to service_role;

-- cowatch_pool_entry -- (addressed rows, ADR 0022)
drop policy if exists "author and addressee" on public.cowatch_pool_entry;
drop policy if exists "author and addressee while entitled" on public.cowatch_pool_entry;
create policy "author and addressee while entitled" on public.cowatch_pool_entry
  for all to authenticated
  using ((auth.uid() = user_id or auth.uid() = recipient_id) and (auth.jwt()->>'sync_until')::timestamptz > now())
  with check (auth.uid() = user_id and (auth.jwt()->>'sync_until')::timestamptz > now());
revoke all on table public.cowatch_pool_entry from anon, authenticated, public;
grant select, insert, update, delete on table public.cowatch_pool_entry to authenticated;
grant all on table public.cowatch_pool_entry to service_role;

-- The shared change sequence is only ever advanced by `discard_stale_write()`,
-- which is `security definer`; no client role needs it directly.
revoke all on sequence public.sync_change_seq from anon, authenticated, public;
