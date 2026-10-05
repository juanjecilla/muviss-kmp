# v1 ships local-only, through fastlane, from one tag

Sync is built (ADR 0009, 0020, 0021) and a paywall seam exists (ADR 0018), so a reader will expect the first store release to carry it. It does not. This records why, and how a release reaches the stores.

## Decision

**v1.0 is local-only on every platform.** Release builds keep ADR 0018's build gate shut: `SYNC_ENABLED` and the Supabase keys stay absent from `release.yml`, `NoOpSyncBackend` is bound, and no sync row renders. Paid sync (EPIC 32, #75) arrives later as an ordinary update with its own privacy and Data Safety revision.

**Store publishing goes through fastlane**: `supply` for Google Play, `gym`/`pilot`/`deliver` for the App Store. Listing copy and screenshots live in `fastlane/metadata/` and are the source of truth; `docs/store/LISTING.md` explains them rather than duplicating them.

**One `vX.Y.Z` tag releases everything.** `release.yml` uploads the AAB to Play's *internal* track, uploads a TestFlight build, and creates the GitHub Release with the desktop installers. The platform jobs are independent: a broken iOS leg does not stop the Play upload. Nothing reaches the public automatically — Play production is a separate `workflow_dispatch` (staged rollout), and App Store review and TestFlight external groups stay manual.

### Why local-only first

Turning sync on makes accounts real, and accounts drag in in-app account deletion and a public deletion URL (Play), Sign in with Apple (App Store 4.8), a privacy policy and Data Safety form that declare library data leaving the device, a Supabase deploy pipeline and RevenueCat on two stores. That is the longest pole in the roadmap. Everything else a store needs — signing, listing, screenshots, privacy URL — is independent of it, and the current privacy story ("no accounts, nothing leaves the device but optional crash reports") is true today. Shipping it now costs nothing that has to be undone: an update can add sync; it cannot remove a misdeclared Data Safety form from review history.

### Why fastlane, not gradle-play-publisher

gradle-play-publisher is Gradle-native and avoids Ruby, but it is a Gradle plugin, and this repo runs AGP 9 / Gradle 9.1 ahead of what most plugins have been tested on. Publishing would break whenever the toolchain moves. `supply` only consumes a built `.aab` and talks to the Play Developer API, so toolchain churn cannot reach it; and the same tool covers iOS, so both stores share one metadata layout and one way of working.

### Why one tag

Version numbers are already derived from git for every target (`git describe` / `rev-list --count`). Per-platform tags would mean per-platform version lines, and a user on two devices would see two versions of the same release. A store-specific hotfix is still a new patch tag; the other stores simply get an identical build they may choose not to promote.

## Consequences

- `docs/PRIVACY.md` and `docs/store/DATA_SAFETY.md` stay as written until EPIC 32 rewrites them in the change that turns sync on.
- EPIC 35 (#78) and EPIC 36 (#79) no longer carry Sign in with Apple, the in-app purchase product or the sync redirect ports; those belong to EPIC 32.
- CI needs Ruby (a `Gemfile` pinned with `Gemfile.lock`) and two new secrets: a Play service-account JSON and an App Store Connect API key.
