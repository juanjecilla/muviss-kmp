-- #129: the addressee of a co-watch row may read it, and nothing else.
--
-- 20260921000000_cowatch.sql gave both tables one `for all` policy:
--
--     using      (auth.uid() = user_id or auth.uid() = recipient_id)
--     with check (auth.uid() = user_id)
--
-- `with check` only guards the row being *written*, so it stops an addressee
-- inserting or editing a row as the author. DELETE has no new row: it is
-- governed by `using` alone, and `using` admits the addressee. So B could
-- hard-delete A's half of their link and every pool entry A addressed to B —
-- the opposite of that migration's own rule, "each owned by its author:
-- neither side can write the other's". pgTAP (supabase/tests/database/
-- sync_rls.test.sql) caught it the first time it ran against Postgres.
--
-- One policy per command instead: read by author or addressee, every write by
-- the author only. Nothing in the client hard-deletes these rows (deletes are
-- soft, `deleted = true`, ADR 0020), so this narrows nothing the app does.
drop policy if exists "author and addressee" on public.cowatch_link;
create policy "author and addressee read" on public.cowatch_link
  for select using (auth.uid() = user_id or auth.uid() = recipient_id);
create policy "author inserts" on public.cowatch_link
  for insert with check (auth.uid() = user_id);
create policy "author updates" on public.cowatch_link
  for update using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "author deletes" on public.cowatch_link
  for delete using (auth.uid() = user_id);

drop policy if exists "author and addressee" on public.cowatch_pool_entry;
create policy "author and addressee read" on public.cowatch_pool_entry
  for select using (auth.uid() = user_id or auth.uid() = recipient_id);
create policy "author inserts" on public.cowatch_pool_entry
  for insert with check (auth.uid() = user_id);
create policy "author updates" on public.cowatch_pool_entry
  for update using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "author deletes" on public.cowatch_pool_entry
  for delete using (auth.uid() = user_id);
