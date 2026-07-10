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
| App info and performance (crash logs, diagnostics) | Yes, optionally | Yes, to Sentry | Only when a crash/error occurs in a release build with a Sentry DSN configured. Contains a stack trace + app version + device model/OS version. No collection/progress data, no TMDB query content. |
| Device or other IDs | No | No | Muviss does not read `ANDROID_ID`, advertising ID, IMEI, etc. |
| Location | No | No | — |

\* TMDB search/detail requests necessarily include the query text or a TMDB
media ID as part of the API call — that is TMDB's traffic, not data Muviss
collects, stores, or shares for its own purposes.

## Is all user data encrypted in transit?

**Yes.** All network calls (TMDB, Sentry) use HTTPS.

## Does your app provide a way for users to request data deletion?

There is no account and no server-side data to delete. Locally, uninstalling
the app removes the on-device database entirely. This is stated in
`docs/PRIVACY.md`.

## Third parties data is shared with

- **TMDB** (themoviedb.org) — search queries / media IDs, to fetch metadata.
- **Sentry** (sentry.io) — crash reports, only in release builds with
  `SENTRY_DSN` configured (see `docs/RELEASING.md`).

## Target audience / content rating

Not determined by this document — set from the Play Console's content rating
questionnaire based on the app's actual content (movie/TV metadata, no
user-generated content, no social features).
