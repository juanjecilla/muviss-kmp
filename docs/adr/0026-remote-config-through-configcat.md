# Remote config through ConfigCat, split from analytics

Every value the app can vary today is fixed at build time (`MuvissBuildConfig`, ADR 0007) or stored per device (`appSettings`). Two needs made that insufficient: an optional feature (an Own TMDB Key, EPIC 46) that must be switchable without a release, and our own TMDB credential, which ships in every binary — if TMDB revokes or blocks it, every install stops working until a new build reaches every store.

## Decision

Remote config is served by **ConfigCat** (`com.configcat:configcat-kotlin-client`), read only through the existing `FeatureFlags` seam. Every remote value has a compiled default that applies offline and before the first fetch; the last good config is cached. Where a value also has a build flag (`BYOK_ENABLED`), the build flag is the compiled default and the remote value overrides it. No user object is passed: evaluation is local, so ConfigCat learns nothing but that a device fetched the config.

This is split out of #29, which bundled remote flags with analytics. Analytics reverses a stated product principle and needs its own ADR and a consent flow; remote config collects nothing about the user and should not wait for that.

## Considered options

- **Firebase Remote Config via GitLive's `firebase-config`.** Free and generous, but `jvm` and `wasm-js` exist only in the `3.0.0-alpha` line (the stable 2.x line has neither), on a toolchain already at the bleeding edge. It also brings a Firebase Installation ID — a device identifier this app does not otherwise have, and a Data Safety change.
- **A static JSON file on muvissapp.com.** No vendor and no SDK, works on every target with plain Ktor. Rejected for now only because it offers no gradual rollout or targeting; it remains the fallback if ConfigCat's free tier or SDK becomes a problem, and swapping it in is contained by the seam.

ConfigCat was the only option publishing stable artifacts for all six targets (verified from its Maven Central `.module`, 5.1.0).

## Consequences

- A request to ConfigCat's CDN reveals the device's IP address; `docs/PRIVACY.md` and the store data-safety answers must say so.
- The ConfigCat SDK key ships in the binary, like the TMDB credential. It must be a read-only key.
- A served TMDB credential is no more secret than one in the APK. The point is rotation, not secrecy.

## Amendment, 2026-10-12 (grilled before building EPIC 45)

- **The seam is `RemoteConfig`, not `FeatureFlags` alone.** `FeatureFlags` is per-device preferences the user writes; remote values are served to every install and nothing local can change them, so they get a read-only `RemoteConfig` in `:core:common` (ConfigCat behind it in `:core:remoteconfig`). `FeatureFlags.byokEnabled` is the one place the two meet, delegating to it. "Read only through the existing `FeatureFlags` seam" above is superseded; the vendor is still known to one module only.
- **Manual poll.** The SDK never fetches by itself. The app asks at startup and on foreground, at most once an hour. **Background paths never ask** (`NewEpisodesWorker`, `SyncWorker`, the iOS BGTask, the desktop timer): they read what the last foreground stored, so a rotated token reaches them only after the app has been opened.
- **The last good config is our own table.** The SDK's default cache is empty on Android and the JVM, so `remoteConfigCache` (`16.sqm`) holds it. It is a cache in ADR 0015's sense: not synced, not exported, hard-deleted by "Delete all data".
- **A rotated credential must win on a cold start.** `TmdbCredentialSource` awaits the stored copy (one local read) before choosing, instead of reading the compiled default. It is asked once per request, so a change applies without a restart.
- **No key, no vendor.** A blank `CONFIGCAT_SDK_KEY` binds a defaults-only implementation that makes no request; the owner creates the product later (`docs/RELEASING.md` §12).
- The SDK's own transitive `ktor-client-android` (3.1.1) rides along on Android but is never used: the SDK is handed the app's engine.
