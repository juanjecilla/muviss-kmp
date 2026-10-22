# Play Console — Data Safety questionnaire answers

Reference answers for the Play Console "App content" → "Data safety" form.
Fill the actual form by hand in the Play Console (it's a UI, not a file), but
every answer should trace back to a line here so it stays consistent with
`docs/PRIVACY.md` and doesn't drift as the app changes.

## Does your app collect or share any of the required user data types?

**No.**

Muviss has no accounts, no backend, and no analytics/advertising SDKs. The
only data that ever leaves the device is what's described below, and none of
it identifies a user.

## Data types

Walking the Play Console's standard categories:

| Category | Collected? | Shared? | Notes |
|---|---|---|---|
| Personal info (name, email, address, etc.) | No | No | No accounts exist. |
| Financial info | No | No | — |
| Health and fitness | No | No | — |
| Messages | No | No | — |
| Photos and videos | No | No | Poster art is fetched *from* TMDB, never uploaded. |
| Audio | No | No | — |
| Files and docs | No | No | — |
| Calendar | No | No | — |
| Contacts | No | No | — |
| App activity (in-app search history, installed apps) | No* | No | Search queries are sent to TMDB to perform the search (see below) but are not logged, stored server-side, or associated with a user identifier by Muviss. |
| Web browsing | No | No | — |
| App info and performance (crash logs, diagnostics) | Yes, optionally | Yes, to Sentry | Only when a crash/error occurs in a release build with a Sentry DSN configured. Contains a stack trace + app version + device model/OS version. No collection/progress data, no TMDB query content. Users can opt out in Settings → Privacy → "Send crash reports" (default on), effective immediately. Stored in Sentry's EU region. Web is not a launched platform (deferred). |
| Device or other IDs | No | No | Muviss does not read `ANDROID_ID`, advertising ID, IMEI, etc. |
| Location (approximate) | Yes, optionally | Yes, to Sentry | Not read by the app. Sentry derives country/region/city from the connection a crash report arrives over and stores it with the report; the IP itself is not stored. Crash diagnostics only; same opt-out as crash reports. Precise location: No. |

\* TMDB search/detail requests necessarily include the query text or a TMDB
media ID as part of the API call — that is TMDB's traffic, not data Muviss
collects, stores, or shares for its own purposes.

## Is all user data encrypted in transit?

**Yes.** All network calls (TMDB, Sentry, ConfigCat) use HTTPS.

## Does your app provide a way for users to request data deletion?

There is no account and no server-side data to delete. Locally, Settings →
Data → Delete all data removes everything the app keeps, and so does
uninstalling. This is stated in `docs/PRIVACY.md`.

## Third parties data is shared with

- **TMDB** (themoviedb.org) — search queries / media IDs, to fetch metadata.
  If the user supplies their own TMDB key (EPIC 46), it is sent to TMDB the
  same way Muviss's own is; it is stored encrypted on the device and never
  leaves it for any other destination.
- **Sentry** (sentry.io) — crash reports, only in release builds with
  `SENTRY_DSN` configured (see `docs/RELEASING.md`).
- **ConfigCat** (configcat.com) — Muviss downloads its remote configuration
  (EPIC 45, ADR 0026), only in release builds with `CONFIGCAT_SDK_KEY`
  configured. **No data is sent about the user**: no identifier, and the values
  are evaluated locally. The request exposes the device's IP address to
  ConfigCat as any request does; Muviss stores none. This is not "data
  collected" in Play's sense (nothing is sent *from* the app about the user),
  but record it here so the answer stays consistent with `docs/PRIVACY.md`.

- **Tally** (tally.so, or Typeform if the provider is switched in Remote Config)
  — only when the user opens **Settings → About → Request a feature**. Muviss
  hands the system browser a link; nothing is posted from the app. The link
  carries the app version, platform, OS version (where the app can read it;
  not on the web) and app language (no
  identifier, nothing from the library), and the provider gets those values when
  the page loads (Tally with the page request; Typeform from its page script,
  since a URL fragment is not sent with the request). What the user then types is theirs to
  submit. Not "data collected" by the app in Play's sense, but recorded so the
  answer stays consistent with `docs/PRIVACY.md`.

## Target audience / content rating

Not determined by this document — set from the Play Console's content rating
questionnaire based on the app's actual content (movie/TV metadata, no
user-generated content, no social features).
