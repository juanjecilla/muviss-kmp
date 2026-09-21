# Co-watch is one hole in the no-social rule

Muviss has promised "no social features" since day one — in `CONTEXT.md`, `README.md`, `CLAUDE.md`, `docs/DESIGN_BRIEF.md`, `docs/EPICS.md`'s parity table (*"No — excluded by product principle"*) and, in words a store reviewer could quote back, the published listing: *"No social features — no feeds, no friends, no comments. This is a personal tool, not a community."*

Co-watch (EPIC 41) makes one user's data reachable by another user for the first time. **The principle is not retired.** What follows is the shape of the single exception, and the four invariants that keep it from growing into a social product by accident.

## The exception, stated narrowly

Two accounts may link, by mutual consent, and each may publish a **Watch Pool** — a list of titles they would watch with that person — addressed to the other. Each device then computes a **Shortlist** locally. That is all. No feeds, no profiles other people can see, no activity, no comments, no follows, no discovery of who else uses the app.

## The four invariants

1. **Read-only inbound.** A Companion's data is rendered, never applied. Nothing another user publishes writes your `WatchProgress`, your `CollectionEntry`, or anything else you own. ADR 0005's stored truth stays yours alone, which also means a mistake on their phone cannot corrupt your play counts or your watch streak.
2. **No co-owned state.** Every row has exactly one writer. Pins are per-person — a pin is a field in *your* pool that the other side renders, and "we both pinned it" is a computed agreement, not a shared document. This is what keeps ADR 0009's last-write-wins honest: it explicitly justified whole-row LWW by this being *"a personal, mostly-single-user-at-a-time tracker, not a live collaborative doc"*, and a co-owned, reorderable list would have made that false by construction.
3. **Purpose-limited.** Only the Watch Pool crosses the wire. Raw watch history, episode ticks, plays, ratings and notes never leave the device. Revocation therefore means something: stop publishing, and there is nothing left server-side to read.
4. **Flat policies only.** Every RLS policy stays a direct `auth.uid()` comparison. No subquery, no `security definer` RPC. There is no app server and no second line of defence, and the anon key ships in the binary — RLS *is* the access-control story, so it stays simple enough to read and prove.

## Consequences

**Addressed rows, not shared rows.** A published pool names its recipient, so the policy is `using (auth.uid() = user_id or auth.uid() = recipient_id) with check (auth.uid() = user_id)`. Writes still check `user_id` alone, so no one can forge a row as someone else. The cost is duplication: N companions means N copies of a pool. Accepted, deliberately, over a link-table subquery whose correctness would depend on a second table's integrity — the failure mode there is a silent cross-user read, which `scripts/sync/verify-rls.sh` can only catch if someone runs it.

**The invite code is self-describing** — the inviter's `user_id` plus a nonce. RLS cannot express "readable if you know the secret" without an RPC this project has never had, so the code carries what a lookup would have returned. Your `user_id` is therefore in any code you send, and someone holding it could address rows at you; that is bounded by mutual confirmation and by only rendering acceptances whose nonce matches a code you actually issued.

**A separate seam, not a bigger change set.** `SyncChangeSet` means *the user's own change-log*, and `SyncChangeSetShapeTest` exists to keep it that way. Companion data is a different animal — addressed, and read-only on the way in — so it gets `CompanionBackend` beside `SyncBackend`, sharing the Postgrest client, token refresh and `server_seq` paging from ADR 0020.

**The Shortlist is not WatchNext.** `CLAUDE.md` says "what do I watch next" has one implementation, and that stays true: `WatchNextUseCase` answers *which show am I mid-way through*, over `WATCHING`. The Shortlist answers *what should two people start*, over `NOT_STARTED` and `WATCHED` — precisely the sets WatchNext excludes. They are different questions and must not be folded together.

**Revisit Willingness is stored intent, and is not a Rewatch.** A `WATCHED`/`FINISHED` title can never re-enter WatchNext, and recording a play does not move `seenEpisodes`, so without new state every title either person has finished would be invisible to this feature — which is most of what two people actually rewatch together. The new column is deliberately *not* called a rewatch flag: `Rewatch` in this codebase is derived from Plays and never stored, and keeping events, derivations and intent apart is the whole point of that split.

**Both sides must be entitled.** This is a sync feature and prices like one; `EntitlementGate` inside `SyncEngine` stays the only check (ADR 0018). The honest cost is that the feature does nothing until two people pay.

**Staleness is labelled, not solved.** There is no push channel, so only a Companion's own device can publish their pool. No work on your device can freshen their data. The UI says when it last changed rather than pretending otherwise.

**The privacy documents become false.** `docs/PRIVACY.md` and `docs/store/DATA_SAFETY.md` describe an app with no accounts and no server-side data, and `docs/store/LISTING.md` promises no social features outright. All three must be amended before any build that ships this is published — ADR 0018 already flagged the first two for sync alone.

## Considered and rejected

**Full library replication.** Simplest to build, since it nearly reuses the existing change set. Rejected: it puts a permanent offline copy of everything you have ever watched on another person's device, and no unlink can claw it back.

**A server-side join.** The most private shape — neither side sees the other's data. Rejected as unavailable: there are no edge functions, no RPCs and no Realtime; the only server-side code in the project is the `discard_stale_write()` trigger. Standing up a compute tier is a larger decision than this feature.

**End-to-end encryption of the pool.** Strongest guarantee, and it would make an RLS mistake harmless. Rejected for now: no key storage across devices, no recovery story, and a lost key breaks a link silently with nothing to diagnose. Worth revisiting if the exception ever widens.

**A co-owned shared list.** What "shared list" sounds like, and what a first pass at this design chose. Rejected on reflection: whole-row last-write-wins means one person's reorder silently discards the other's, and it would have been the only co-owned state in the product.

**Cross-ticking "we watched this together".** Genuinely useful, and the obvious next request. Excluded by invariant 1 — RLS forbids writing another user's rows anyway, so it would have to be a signal their device applies, and the first version of this feature does not get to move the source of truth.
