-- EPIC 32 (#75, ADR 0019): the entitlement mirror and the claim it feeds.
--
-- RevenueCat owns whether an account has paid for sync. This table is a
-- MIRROR of that answer, written by exactly one thing — the
-- `revenuecat-webhook` Edge Function, with the service role — so that a
-- desktop client (which never sells) and the server's own policies can know it
-- without a RevenueCat secret in any binary.
--
-- Nothing reads this table per row of a synced table. The custom access token
-- hook below reads it once per token and stamps the answer into the JWT as
-- `sync_until`; the synced tables' policies (next migration) compare against
-- that claim, which keeps ADR 0022's invariant 4 — every policy a flat
-- comparison, no subquery, no `security definer` — intact.
--
-- Nothing has shipped against this schema (sync is gated off in every release
-- build, ADR 0018), which is what makes adding it this way safe.

create table if not exists public.entitlement (
  user_id uuid primary key references auth.users(id) on delete cascade,
  -- Whether the `sync` entitlement is currently granted. `false` is a lapse
  -- (EXPIRATION, or the losing side of a TRANSFER), not "never paid": a user
  -- who never paid simply has no row.
  active boolean not null,
  -- When the grant ends. Null with `active` means a grant with no end date
  -- (a promotional or lifetime grant) — the hook renders it as a far-future
  -- claim rather than omitting it.
  expires_at timestamptz,
  updated_at timestamptz not null default now(),
  -- `<EVENT_TYPE>:<RevenueCat event id>` of the event that produced this row,
  -- for diagnosis. RevenueCat reuses the id on retries, so a redelivered event
  -- writes the same value again.
  source_event text,
  -- RevenueCat's `event_timestamp_ms` for that event. Webhooks are delivered
  -- at least once and in no guaranteed order, so the trigger below refuses an
  -- event older than the one already applied — the same last-write-wins
  -- shape `discard_stale_write()` gives the synced tables.
  source_event_at timestamptz
);

alter table public.entitlement enable row level security;

-- A client may read its own row (to learn that a purchase it just made has
-- landed, and only then refresh its session — ADR 0019). There is
-- deliberately NO insert, update or delete policy: with RLS on, no policy
-- means no client can write, and the service role (which bypasses RLS) is the
-- only writer.
drop policy if exists "own row readable" on public.entitlement;
create policy "own row readable" on public.entitlement
  for select to authenticated
  using (auth.uid() = user_id);

-- Table privileges are explicit rather than inherited from Supabase's default
-- privileges (`auto_expose_new_tables = false` in config.toml): anon gets
-- nothing, authenticated may only select, the service role writes.
revoke all on table public.entitlement from anon, authenticated, public;
grant select on table public.entitlement to authenticated;
grant select, insert, update, delete on table public.entitlement to service_role;

-- Out-of-order and duplicate webhook deliveries. An update whose event is
-- strictly older than the stored one is rewritten back to the stored row and
-- the upsert still returns 2xx, so RevenueCat stops retrying it. An equal
-- timestamp is accepted, which is what makes a retry of the same event a
-- no-op rather than an error.
create or replace function public.discard_stale_entitlement_event()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if tg_op = 'UPDATE'
     and old.source_event_at is not null
     and new.source_event_at is not null
     and new.source_event_at < old.source_event_at then
    return old;
  end if;
  new.updated_at := pg_catalog.now();
  return new;
end;
$$;

revoke execute on function public.discard_stale_entitlement_event() from anon, authenticated, public;

drop trigger if exists entitlement_event_order on public.entitlement;
create trigger entitlement_event_order
  before insert or update on public.entitlement
  for each row execute function public.discard_stale_entitlement_event();

-- The custom access token hook (Supabase Auth's documented contract): GoTrue
-- calls it with `{ user_id, claims, authentication_method }` every time it
-- mints an access token — sign-in and every refresh — and uses the `claims`
-- of what it returns.
--
-- `sync_until` is present only while the account is entitled, and is then
-- the grant's end as an ISO-8601 UTC instant. A lapsed, expired or absent
-- entitlement OMITS the claim (any copy in the incoming claims is removed),
-- so every policy comparing against it fails closed: a null cast compares as
-- null, which RLS treats as false. Active with no end date renders as
-- 9999-12-31, so the policies need only one shape.
--
-- Not `security definer`: GoTrue runs it as `supabase_auth_admin`, which is
-- granted exactly the select it needs below. Clients cannot call it (execute
-- is revoked from them), so it is not a way to read anyone's entitlement.
create or replace function public.custom_access_token_hook(event jsonb)
returns jsonb
language plpgsql
stable
set search_path = ''
as $$
declare
  claims jsonb := coalesce(event->'claims', '{}'::jsonb) - 'sync_until';
  grant_active boolean;
  grant_ends timestamptz;
begin
  select e.active, e.expires_at
    into grant_active, grant_ends
    from public.entitlement e
   where e.user_id = (event->>'user_id')::uuid;

  if grant_active and (grant_ends is null or grant_ends > pg_catalog.now()) then
    claims := pg_catalog.jsonb_set(
      claims,
      '{sync_until}',
      pg_catalog.to_jsonb(
        pg_catalog.to_char(
          coalesce(grant_ends, '9999-12-31 23:59:59+00'::timestamptz) at time zone 'UTC',
          'YYYY-MM-DD"T"HH24:MI:SS"Z"'
        )
      )
    );
  end if;

  return pg_catalog.jsonb_set(event, '{claims}', claims);
end;
$$;

grant usage on schema public to supabase_auth_admin;
grant execute on function public.custom_access_token_hook(jsonb) to supabase_auth_admin;
revoke execute on function public.custom_access_token_hook(jsonb) from anon, authenticated, public;

grant select on table public.entitlement to supabase_auth_admin;
-- `supabase_auth_admin` does not bypass RLS, so the hook needs a policy of its
-- own. It is scoped to that role, so it grants clients nothing.
drop policy if exists "auth hook reads entitlements" on public.entitlement;
create policy "auth hook reads entitlements" on public.entitlement
  for select to supabase_auth_admin
  using (true);
