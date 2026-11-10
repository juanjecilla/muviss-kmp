# Sync: what stands between today and production

Issue #725 (checklist). Assessment of 2026-11-09; re-verify before acting on a row.

## Is RevenueCat ready?
No part of it exists in the repository. There is no RevenueCat dependency in any build file or `libs.versions.toml`; `:core:billing` binds `AlwaysEntitledProvider` (when `SYNC_ENTITLEMENT_OVERRIDE` is set) or the fail-closed `NoEntitlementProvider`; the paywall is `HandRolledPaywall` on all six targets; ADR 0019 is accepted and not implemented. Whether the RevenueCat dashboard, Play subscription and App Store product exist cannot be read from the repo: that is the owner's checklist below (#66).

## Where things stand
| Area | State |
|---|---|
| Sync engine, server sequence, opt-in auto sync | done, compiled out of releases (ADR 0009/0020/0021/0024) |
| Dev and prod Supabase projects | done (#151) |
| RLS incl. co-watch fix | done, pgTAP in CI (#129) |
| RLS on `taste_profile` | base policy exists with no pgTAP of its own (#586); #283's branch gates it (`20261027000100`, covered by `sync_entitlement.test.sql`); its two older migrations still need re-timestamping at release-merge time (#727) |
| Server entitlement gate (#283) | draft, held until the release that turns sync on; hook must be live first (#282) |
| Client entitlement, Lapsed UI, account deletion UI | missing (#279, #285, #287) |
| RevenueCat provider + paywall | missing (EPIC 32, #75) |
| Prod auth providers | `config.toml` has Apple (disabled) only; Google/GitHub absent; captcha commented out; `seed.sql` referenced, missing |
| Account deletion | server function lives in #283; no public web page (Play requires one) |
| Privacy | `docs/PRIVACY.md` line 6 says no accounts; `docs/store/DATA_SAFETY.md` same; #467 contact address |
| Release builds | set none of the sync flags (`android-release-build.yml`); `release.yml` writes "Sync is compiled out" |
| TMDB licence | #302, needed before charging |
| Live checks | token refresh past expiry, fresh-install restore, Android upgrade never run (#100, #114) |
| Data parity | Delete all data vs. sync race #677/#678; Display Preferences #643, Muted Genres #644 (after #642) |

## Suggested order
1. Owner: RevenueCat project, entitlement id **`sync`** (the #283 webhook, `SYNC_ENTITLEMENT_ID`, ignores events for any other id, so a different choice fails the purchase-to-sync check unless the webhook changes too), offering, Play subscription, App Store product, webhook secret (#66). Nothing in code can be tested against real stores before this.
2. Dev Supabase: enable the access-token hook (#282), merge-test #283's migrations on dev only.
3. `RevenueCatEntitlementProvider` in `:core:billing` `androidMain`/`iosMain` only. `purchases-kmp` publishes Android + iOS artifacts, and a `commonMain` dependency breaks `compileKotlinWasmJs` for every downstream module (CLAUDE.md). Check licensee's Apache-2.0/MIT allow-list first.
4. Real paywall presenter behind `rememberPaywallPresenter`; desktop can hold an entitlement but not sell one; **web is blocked** because OAuth sign-in throws before it starts (#46), so a web user cannot obtain the account entitlement until #46 lands.
5. Client entitlement states and account deletion (#279, #285, #287), then the public deletion page.
6. Live checks on dev: token refresh past expiry, fresh-install restore and the Android upgrade (`docs/SYNC.md` "Still open"; #100 and #114 cover only part of it), against the real stores' sandbox where the check needs one.
7. **Ship the schema first.** Before merging #283, compare hosted dev's migration history with the final migration files: the original versions were applied to dev under their first timestamps, and `supabase db push` fails when the recorded versions differ from the files. Confirm the schema is the same, then reconcile (`supabase migration repair`, dev only). A release that carries #283's migrations to prod through the release train (re-timestamped, #727) with sync still compiled out. The gate fails closed, and no released client uses sync, so nobody is affected; it is what lets step 8 exist.
8. **Production entitlement rollout** (#282): enable the access-token hook on prod, deploy `revenuecat-webhook` and `delete-account`, set the webhook secret, then verify purchase to sync end to end with a sandbox account on prod, **on both Android and iOS** (EPIC #75 requires sandbox purchases on both stores; one platform's passing proves nothing about the other's purchase path). The webhook accepts only `PRODUCTION` events unless `REVENUECAT_ALLOWED_ENVIRONMENTS` lists `SANDBOX` too (default `PRODUCTION`, `revenuecat-webhook/logic.ts` on #283's branch), so set it for the check and take `SANDBOX` out again after the check; configure the RevenueCat webhook URL and its authorization header before the purchase. `authorized()` in `revenuecat-webhook/logic.ts` compares the **whole** `Authorization` value to `REVENUECAT_WEBHOOK_SECRET` and does not strip a `Bearer ` prefix, so RevenueCat's header value must be exactly the stored secret (or the secret must be stored with the prefix); a mismatch is a 401 and no entitlement is created.
9. **Sign-in providers.** ADR 0019 requires Google, Apple and GitHub. `config.toml` has Apple (disabled) only, so implement and configure each provider in production, then verify a real sign-in with each one: a purchase test through one provider proves nothing about the others.
10. Privacy, data-safety, listing.
11. **Gate on iOS.** ADR 0019 puts iOS in the App Store before the flags move, so Android and iOS gain sync together (#78). Only then flip the release flags, in a dedicated release.

## Hazards
- Merging a `release/*` into `main` applies migrations to **prod** automatically; #283's policies lock everyone out until the hook is on. Harmless only while sync is compiled out of every release, which is why step 7 comes before the flags and not with them.
- #283's migration timestamps must sort after develop's newest at merge time (#727).
- Paid features (sync, co-watch) never appear on the landing, before or after they ship; the Play listing is a separate question and follows its own timing (CLAUDE.md).
