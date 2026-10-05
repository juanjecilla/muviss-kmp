# Store listing

**The copy lives in `fastlane/metadata/android/<locale>/`** (`title.txt`,
`short_description.txt`, `full_description.txt`) and is uploaded by fastlane
on every tag (ADR 0024). This file explains it; it does not duplicate it, so
the two cannot drift. `scripts/store/check-listing.sh` enforces Play's limits
(title 30, short 80, full 4000 characters) and runs before every upload and in
`cut-release.sh`.

Release notes are per tag: `fastlane/release-notes/<tag>/<locale>.txt`, max
500 characters, one per listing locale (see `docs/RELEASING.md` §5).

## What the copy may and may not claim

Rewritten 2026-10-05 for v1. It describes what a **release build** does, which
is narrower than what the code can do:

- **No sync, no account, no co-watch.** Release builds compile sync out
  (ADR 0018, ADR 0024). "No account" and "no social features" are true for v1
  and are what `docs/store/DATA_SAFETY.md` and `docs/PRIVACY.md` declare;
  EPIC 32 (#75) must rewrite all four together when sync ships, and EPIC 41
  (co-watch) must revisit the "no social" line (ADR 0022).
- **No language claim.** Only the navigation is translated so far (EPIC 31,
  #74); the Spanish listing exists, but saying "available in Spanish" waits
  until the app is.
- **No "cast" claim.** Only episodes show guest cast and crew; titles do not.
- **The TMDB attribution line stays** in every locale (TMDB's terms).
- New-episode notifications are claimed: they exist on Android, which is the
  only platform this listing is for.

## Screenshots and feature graphic

Generated, not captured: a `StoreShot` mode of the golden harness renders
seeded screens into `fastlane/metadata/android/<locale>/images/` (EPIC 33,
#76). Seed data uses public-domain films with public-domain poster scans and
fictional shows with generated art — no real TMDB posters in marketing
material. Planned set, in display order: Library (mixed statuses), title
detail with episode ticks, Progress (watch next), Triage, Search/Discover,
Profile stats; light and dark; plus the 1024×500 feature graphic.

## App Store (iOS) — EPIC 11 / issue #13

Same English/Spanish copy (the fastlane files) reused verbatim where App Store Connect's
fields match Play's — App Store just slices it slightly differently.
See `docs/RELEASING.md`'s new iOS section for the archive/TestFlight/
signing steps this listing gets attached to.

### Field mapping

| App Store Connect field | Source |
| --- | --- |
| Name | `Muviss` |
| Subtitle (max 30 chars) | `Track movies & TV, privately` (29 chars) — a compressed version of the short description; App Store has no separate "short description" field the way Play does, just this + the promotional text below. |
| Promotional text (max 170 chars, editable without a new review) | `Muviss is a private tracker for movies and TV shows. No account, no social, no ads — just tracking.` (~100 chars) |
| Description (max 4000 chars) | `fastlane/metadata/android/<locale>/full_description.txt`, verbatim (same 4000-char budget as Play); EPIC 35 (#78) moves it into deliver's layout. |
| Keywords (max 100 chars, comma-separated, not shown to users) | `movie tracker,tv tracker,watchlist,episode tracker,watch progress,tv shows,movies` |
| What's New (per version) | Written per release once there's a changelog to summarize; not produced yet (no tagged releases exist). |
| Privacy Policy URL | `https://muvissapp.com/privacy/` — the landing renders it from `docs/PRIVACY.md` at build time (same document Play's Data Safety answers in `DATA_SAFETY.md` trace back to), so the two cannot drift. Live once #169 is done. |
| App Store category | Entertainment (primary); no secondary category needed. |
| Age rating | No objectionable content, no user-generated content, no gambling — the "4+" questionnaire path (no mature content declared). |

### App Store "Privacy" (Nutrition Label) questionnaire

Same answer as Play's Data Safety form (`DATA_SAFETY.md`): Muviss collects
**no data** — no accounts, no backend, no analytics/advertising SDKs. The
only thing that ever leaves the device is an optional, anonymous Sentry
crash report if `SENTRY_DSN` is configured (see `docs/RELEASING.md` item
4), which the person can switch off in Settings → Privacy → "Send crash
reports" (EPIC 26) — declare **Crash Data**, linked to no identity, used for App
Functionality only, matching how `DATA_SAFETY.md` frames the same fact for
Play.

### Screenshot sizes (App Store Connect requires per-device-class sets)

Reuse the same shot list/order as the Play screenshot set above —
just captured at App Store Connect's required sizes instead of Play's
single flexible size:

- [ ] 6.9"/6.7" display (iPhone 16 Pro Max class) — required.
- [ ] 6.5" display (iPhone 11 Pro Max/XS Max class) — required if no
      6.9"/6.7" set is uploaded for older-device fallback; safest to
      provide both.
- [ ] iPad Pro 13"/12.9" — only needed if the App Store listing claims
      iPad support (it does — `TARGETED_DEVICE_FAMILY = "1,2"` in
      `project.pbxproj`).

None of these are captured yet — same "not blocking the first internal
release" status as Play's screenshot checklist, but App Store Connect (unlike
Play's internal testing track) **will refuse to submit for review** without
at least the required iPhone set, so this blocks the App Store submission
step specifically (TestFlight internal testing does not require
screenshots — only the eventual public App Store listing does).
