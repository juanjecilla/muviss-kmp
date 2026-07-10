# Privacy Policy — Muviss

_Last updated: 2026-07-10._

Muviss is a personal, offline-first tracker for movies and TV shows. There are
no user accounts, no social features, and no analytics/advertising SDKs. This
document explains the only two ways Muviss talks to the network, and exactly
what leaves your device.

## What Muviss stores

Everything you track — your collection, favorites, and per-episode watch
progress — is stored **only on your device**, in a local SQLite database (see
ADR 0002, "offline-first, local source of truth"). Muviss has no backend of
its own and no account system; nobody but you (and whoever has your device)
can see this data. It is not included in Android's automatic backup either
(`allowBackup="false"` — see the comment in `AndroidManifest.xml`), so it
never leaves the device even incidentally.

## Network calls Muviss makes

1. **TMDB (The Movie Database) API** — [themoviedb.org](https://www.themoviedb.org).
   Used to search for titles and fetch metadata: poster art, synopsis,
   seasons/episodes, release dates. Requests include only the search
   query/title id you're looking up and Muviss's API key — never anything
   that identifies you personally. See [TMDB's own privacy
   policy](https://www.themoviedb.org/privacy-policy) for how they handle
   that traffic.

   This product uses the TMDB API but is not endorsed or certified by TMDB.

2. **Sentry (crash reporting)** — optional, and off by default in developer
   builds (see `docs/RELEASING.md`). When enabled in a release build, a
   crash or unhandled error sends a report to Sentry containing: a stack
   trace, the app version, and basic device info (OS version, device model).
   It does **not** include your collection, watch progress, or any TMDB
   query content. See [Sentry's privacy
   policy](https://sentry.io/privacy/) for how they handle that data.

Muviss makes no other network calls: no analytics, no advertising, no
telemetry, no third-party trackers.

## Data you can delete

Uninstalling the app deletes the local database and everything in it — there
is nothing left on any server, because nothing you enter is ever sent to one.

## Contact

Muviss is developed by Juanje Cilla. Open an issue on the project's GitHub
repository for privacy questions.

## Changes to this policy

This file is versioned alongside the app's source in the repository; the
"last updated" date above always reflects the latest revision.
