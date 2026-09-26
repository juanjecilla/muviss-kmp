-- EPIC 41 (co-watch, ADR 0022, #118): the first tables in this schema that one
-- account may read from another.
--
-- Every existing table carries one identical policy, `using (auth.uid() =
-- user_id) with check (auth.uid() = user_id)`, six times over. These two need a
-- seventh shape, and whatever it is IS the access-control story: there is no
-- app server, no edge function and no second line of defence, and the anon key
-- ships in the client binary, so RLS is the only boundary there is.
--
-- The shape chosen is ADDRESSED ROWS. A row names the single account it is for,
-- and the policy stays a flat comparison against two columns:
--
--     using      (auth.uid() = user_id or auth.uid() = recipient_id)
--     with check (auth.uid() = user_id)
--
-- Reads are allowed to the author and to the one named recipient. Writes still
-- check `user_id` alone, so nobody can forge a row as somebody else, and
-- nobody can hand themselves a read by writing a row that claims to be from
-- someone. There is no subquery and no `security definer` function to get
-- wrong: the alternative, a policy that consults a link table, makes
-- correctness depend on that table's integrity too, and its failure mode is a
-- silent cross-user read.
--
-- The cost, accepted deliberately: a pool is stored once per recipient, so N
-- companions means N copies. See ADR 0022.
--
-- `recipient_id` references `auth.users` so a row can only ever be addressed to
-- an account that exists. That does let a caller distinguish "this uuid is a
-- user" from "it is not"; a uuid is not guessable and the invite code hands one
-- over deliberately, so this leaks nothing the flow did not already give away.
--
-- Nothing has shipped against this schema — sync is gated off in every release
-- build (ADR 0018) and co-watch is additionally blocked on EPIC 32 — which is
-- what makes adding tables this way safe.

-- 0. Two user-authored columns on the existing library table. They are not
-- co-watch's own state: they are the user's, they sync like `rating` and
-- `note`, and the Watch Pool is built from them.
--
-- `revisit_willingness` is NULLABLE ON PURPOSE. Null means the user has never
-- answered for that title, which is a different thing from answering "no" —
-- unanswered falls back to a per-device default, answered does not. That is
-- also why the client pushes nulls explicitly (`explicitNulls = true`,
-- ADR 0020): PostgREST builds its `ON CONFLICT DO UPDATE SET` from the union of
-- keys in the body, so a key omitted from every object is never written and
-- clearing an answer would silently not reach here.
alter table public.collection_entry add column if not exists revisit_willingness boolean;
alter table public.collection_entry add column if not exists cowatch_pinned boolean not null default false;

-- 1. The link. Two rows per relation, one per side, each owned by its author:
-- neither side can write the other's, so "linked" is an agreement read off two
-- independent statements rather than a shared row with two writers.
--
-- `nonce` is the random half of the invite code. It is what stops an
-- unsolicited row addressed at a known user id from presenting as an
-- invitation: the client renders an acceptance only when its nonce matches a
-- code that account actually issued.
create table if not exists public.cowatch_link (
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  recipient_id uuid not null references auth.users(id) on delete cascade,
  state text not null,
  nonce text not null,
  created_at_epoch_ms bigint not null,
  updated_at_epoch_ms bigint not null,
  deleted boolean not null default false,
  server_seq bigint,
  primary key (user_id, recipient_id),
  constraint cowatch_link_not_self check (user_id <> recipient_id)
);

alter table public.cowatch_link enable row level security;

create policy "author and addressee" on public.cowatch_link
  for all
  using (auth.uid() = user_id or auth.uid() = recipient_id)
  with check (auth.uid() = user_id);

-- 2. The Watch Pool: what one account publishes to one Companion. Purpose-
-- limited by construction (ADR 0022's third invariant) — it carries what a
-- Shortlist needs to rank and render, and nothing else. No ticks, no plays, no
-- ratings, no notes, no history.
--
-- `seen` and `started` are booleans about this title only. They are the two
-- bits the ranking function needs ("neither of us has started it") and they
-- deliberately do not carry how far, when, or how often.
create table if not exists public.cowatch_pool_entry (
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  recipient_id uuid not null references auth.users(id) on delete cascade,
  media_id text not null,
  media_type text not null,
  title text not null,
  poster_url text,
  genres text not null default '',
  runtime_minutes integer,
  started boolean not null default false,
  seen boolean not null default false,
  pinned boolean not null default false,
  updated_at_epoch_ms bigint not null,
  deleted boolean not null default false,
  server_seq bigint,
  primary key (user_id, recipient_id, media_id)
);

alter table public.cowatch_pool_entry enable row level security;

create policy "author and addressee" on public.cowatch_pool_entry
  for all
  using (auth.uid() = user_id or auth.uid() = recipient_id)
  with check (auth.uid() = user_id);

-- 3. Same last-write-wins stamping and clamping as every other table (ADR 0020).
-- `discard_stale_write()` also assigns `server_seq`, which is what makes these
-- tables pageable by the same cursor machinery.
drop trigger if exists cowatch_link_lww on public.cowatch_link;
create trigger cowatch_link_lww
  before insert or update on public.cowatch_link
  for each row execute function discard_stale_write();

drop trigger if exists cowatch_pool_entry_lww on public.cowatch_pool_entry;
create trigger cowatch_pool_entry_lww
  before insert or update on public.cowatch_pool_entry
  for each row execute function discard_stale_write();

-- 4. Paging indexes. Unlike the six single-owner tables, these are read by
-- recipient as well as by author, so both directions need one.
create index if not exists cowatch_link_author_seq on public.cowatch_link(user_id, server_seq);
create index if not exists cowatch_link_addressee_seq on public.cowatch_link(recipient_id, server_seq);
create index if not exists cowatch_pool_entry_author_seq on public.cowatch_pool_entry(user_id, server_seq);
create index if not exists cowatch_pool_entry_addressee_seq on public.cowatch_pool_entry(recipient_id, server_seq);
