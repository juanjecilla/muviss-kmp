-- EPIC 32 (#75, ADR 0019), completing 20261022000100_sync_requires_entitlement.sql
-- for a table that did not exist when that migration was written.
--
-- `taste_profile` (20261027000000, #588) arrived with the flat "own rows only"
-- policy that every single-owner table had before the entitlement gate. This
-- sorts after it on purpose: a migration that gates a table must run after the
-- table exists, and re-timestamping the two entitlement migrations to follow
-- 20261027 at release-merge time (#727) leaves this one in order either way.
--
-- Same shape as the other single-owner tables: one flat comparison against the
-- `sync_until` claim, `to authenticated`, privileges made explicit.

drop policy if exists "own rows only" on public.taste_profile;
drop policy if exists "own rows while entitled" on public.taste_profile;
create policy "own rows while entitled" on public.taste_profile
  for all to authenticated
  using (auth.uid() = user_id and (auth.jwt()->>'sync_until')::timestamptz > now())
  with check (auth.uid() = user_id and (auth.jwt()->>'sync_until')::timestamptz > now());
revoke all on table public.taste_profile from anon, authenticated, public;
grant select, insert, update, delete on table public.taste_profile to authenticated;
grant all on table public.taste_profile to service_role;
