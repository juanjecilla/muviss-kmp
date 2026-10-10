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
