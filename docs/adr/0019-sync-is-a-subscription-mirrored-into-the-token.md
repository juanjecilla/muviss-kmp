# Sync is a subscription, and the server enforces it through a claim in the token

Status: accepted (2026-10-10), not yet implemented — EPIC 32, #75.

ADR 0018 made sync a paid feature and put the check inside `SyncEngine`, but
left open what is sold, who knows that it was bought, and what happens to an
account when it changes hands or stops paying. These are the owner's answers,
from the planning session of 2026-10-10, and the reasoning that went with them.

## What is sold

A **subscription**, monthly or yearly, with a seven-day free trial on the
yearly one through the stores' own introductory offers. No lifetime purchase:
sync has a running cost per account and a one-off price would not cover it.
Prices live in the store consoles, not here. Co-watch is part of the same
entitlement and, as ADR 0022 already says, **both** Companions must hold it.

Purchases happen only where a store SDK exists: Android and iOS, through
RevenueCat's `purchases-kmp` in `:core:billing`'s `androidMain`/`iosMain`
(never `commonMain` — it publishes no web artifacts). Desktop never sells;
a desktop user signs in and is entitled because they paid on a phone. Web
stays deferred and gets no web billing in this epic.

## Who knows: RevenueCat, mirrored into Supabase

RevenueCat owns the entitlement, keyed by `appUserID = auth.uid()`. A
RevenueCat webhook calls a Supabase Edge Function, which checks a shared
secret and writes `public.entitlement(user_id, active, expires_at)`. Clients
may read their own row and nobody but that function may write it.

This is **the project's first server-side code**. ADR 0022 counted the
`discard_stale_write()` trigger as the only one and rejected a server-side
join partly because standing up a compute tier was "a larger decision than
this feature". It is the right size for this one: without a mirror, desktop
cannot know anyone paid, and the alternative — each client asking
RevenueCat's REST API — puts a RevenueCat secret in a binary.

## The server enforces it, with flat policies

The gate in `SyncEngine` keeps an honest client honest; it cannot stop a
patched one, and the anon key ships in every binary. So the server checks
too. A Supabase custom-access-token hook reads `entitlement` and stamps a
`sync_until` claim into the user's JWT, and every synced table's policy
becomes

```sql
using (auth.uid() = user_id and (auth.jwt()->>'sync_until')::timestamptz > now())
```

That keeps ADR 0022's invariant 4 — every policy a direct comparison, no
subquery, no `security definer` — which a policy reading `entitlement`
directly would have broken on every synced table. The hook is the one place
that joins, and it runs once per token rather than once per row.

The cost is lag. A lapse takes effect when the current token expires (the
TTL, an hour), not the moment it happens; accepted, since the person paid
for that hour anyway. The other direction matters more: a purchase completes
before the webhook lands, so the client waits for its `entitlement` row to
turn active and then refreshes its session, rather than refreshing once and
reading a token that predates the payment. A push the server refuses for this
reason is `SyncOutcome.NotEntitled`, not `Failed` — it is the paywall, not an
error.

Rejected: a sanctioned exception to invariant 4 (one `exists (select … from
entitlement …)` per policy). Revocation would be immediate, but the
invariant exists because RLS is the whole access-control story, and "flat,
except" is the first step to "flat, mostly".

## When it lapses

The account's server rows are **kept and read-locked**: the policies above
stop matching, nothing is purged, and renewing resumes where it stopped, with
local edits made meanwhile merging by last-write-wins. The device's own copy
was never at risk. Only "Delete account" removes server data. Whether keeping
everything forever stays affordable is EPIC 45 (#257).

## When the account changes

**Signing out keeps the local library.** The app is offline-first and works
signed out; wiping on sign-out would punish the common case (a sign-out to
fix a token, then the same account again) to protect a rare one.

**Signing in as a different account stops and asks** — the engine already
returns `AccountChanged` and pushes nothing. A blocking dialog offers
*Replace with this account's library* (`DiscardLocalData`, the default) or
*Add this device's library to this account* (`MergeLocalDataIntoAccount`).
Cursors reset either way. Replace is the default because the dangerous
direction is the silent one: merging a shared tablet's library into the
wrong person's account cannot be undone from the device, and discarding a
local copy that is already on its own account's server costs a pull.

## Sign-in providers

Google, Apple and GitHub, on every platform. Apple because App Store
guideline 4.8 requires an equivalent privacy-preserving option once any
third-party sign-in exists; Google because it is what most Android users
have; GitHub because it already works and some users want it.

## When it ships

Everything lands dark behind ADR 0018's gates. The release workflow sets
`SYNC_ENABLED`, `SUPABASE_URL` and `SUPABASE_ANON_KEY` only once iOS is in
the App Store (EPIC 35), so Android and iOS gain sync in the same week —
"syncs across your devices" should be true for an iPhone user on day one.
`SYNC_ENTITLEMENT_OVERRIDE` is never set in a release build.
