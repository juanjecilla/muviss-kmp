-- #902 and #790: what a triage verdict wrote into the library, so undo takes back exactly that.
--
-- entry_added_at_epoch_ms: the added-at stamp of the collection entry the verdict created. Undo removes
-- the entry only while its current stamp still matches, so an entry removed and added again while the
-- undo was pending survives. Nullable: a verdict that created no entry has none, and a NULL never
-- authorises a removal.
--
-- ticked_episode_ids: the EpisodeId strings the verdict newly ticked, comma-separated. Undo un-ticks
-- exactly these and leaves the person's own ticks.
--
-- Both must sync with the decision, because undo can run on any device that holds it. A push from a
-- build that predates the columns omits the keys; PostgREST's merge-duplicates upsert leaves an existing
-- row's values alone when the key is absent, so such a push cannot clear a stamp or a list either.
alter table public.triage_decision add column if not exists entry_added_at_epoch_ms bigint;
alter table public.triage_decision add column if not exists ticked_episode_ids text not null default '';
