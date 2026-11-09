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
| RLS on `taste_profile` | policy exists, no pgTAP (#586), and ungated once #283 lands (#727) |
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
1. Owner: RevenueCat project, entitlement id, offering, Play subscription, App Store product, webhook secret (#66). Nothing in code can be tested against real stores before this.
2. Dev Supabase: enable the access-token hook (#282), merge-test #283's migrations on dev only.
3. `RevenueCatEntitlementProvider` in `:core:billing` `androidMain`/`iosMain` only. `purchases-kmp` publishes Android + iOS artifacts, and a `commonMain` dependency breaks `compileKotlinWasmJs` for every downstream module (CLAUDE.md). Check licensee's Apache-2.0/MIT allow-list first.
4. Real paywall presenter behind `rememberPaywallPresenter`; desktop and web can hold an entitlement but not sell one.
5. Client entitlement states and account deletion (#279, #285, #287), then the public deletion page.
6. Privacy, data-safety, listing; flip release flags in a dedicated release.
7. Run the live checks (#100, #114) on dev before the flags move.

## Hazards
- Merging a `release/*` into `main` applies migrations to **prod** automatically; #283's policies lock everyone out until the hook is on.
- #283's migration timestamps must sort after develop's newest at merge time (#727).
- Paid features never appear on the landing or Play listing before they ship (CLAUDE.md).
