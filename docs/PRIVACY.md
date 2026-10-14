# Privacy Policy — Muviss

_Last updated: 2026-09-29._

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

2. **Sentry (crash reporting)** — optional, and you can turn it off. It is on
   by default in release builds, and off in developer builds (which have no
   Sentry project configured, see `docs/RELEASING.md`). **Settings → Privacy →
   "Send crash reports"** switches it off; the change takes effect immediately,
   not at the next launch.

   When it is on, a crash or an unexpected error sends a report to Sentry
   containing: a stack trace, the app version and build, the environment
   (`production` or `development`), and basic device info (OS version, device
   model). It does **not** include your collection, watch progress, or any TMDB
   query content, and no user identifier or cookies are attached.
   Before a report leaves the device, anything that looks like a credential
   (`api_key=…`, `token=…`, `Authorization: Bearer …`) is removed from its
   messages, tags and context, not just its text. Muviss also does not send
   Sentry's separate "release health" session pings (a per-launch heartbeat
   some apps use for a crash-free-session rate) — that switch is off
   unconditionally, on or off, because nothing in the app reads that rate and
   the opt-out above cannot silence it once started (it is not an event, and
   `beforeSend` only ever sees events). Reports are stored in Sentry's **EU region** (Frankfurt, Germany), and the project is set not to store IP addresses.

   One thing Sentry adds on its side: when a report arrives, Sentry looks up
   an **approximate location** (country, region and city) from the network
   connection it came over, and keeps that with the report. The IP address
   itself is discarded and not stored. Muviss uses the location only to tell
   whether a crash is regional; turning crash reports off stops it with
   everything else. See [Sentry's privacy policy](https://sentry.io/privacy/)
   for how they handle that data.

   Per platform:
   - **Android, iOS, desktop** — reporting starts as the app starts, before
     anything else, so a crash during launch or in a background job is covered.
     Your choice is read from the local database at that moment. If the
     database cannot be read at all (it is corrupt, say), Muviss falls back to
     the default, **on**, for that launch — the one case where a crash could be
     reported despite an opt-out.
   - **Web** — same toggle, same scrubbing, but a different SDK and a
     different starting point: the Kotlin Multiplatform SDK the other three
     platforms use ships no real web implementation, so the web build loads
     Sentry's own browser SDK directly from Sentry's CDN
     (`browser.sentry-cdn.com`) instead. That script is fetched **only** on a
     build that has a Sentry project configured — on a build without one
     (every developer build, by default) nothing is fetched and nothing is
     sent, the same as the other platforms. On a build that does have one,
     loading that script is itself a request to a third party
     (Sentry/Fastly), separate from crash reporting itself, exposing your IP
     address to it the way any third-party script on a page would; turning
     the Settings toggle off stops reports from being *sent*, not that one
     script load. Reporting is not covered by an equivalent to the other
     platforms' "starts before anything else" — the web app has no pre-launch
     hook to start it from, so your choice is read moments after the app
     starts rather than before, during which a crash could in principle be
     sent regardless of an opt-out (the same brief window the other platforms
     accept while their own stored choice is still being read).

Muviss makes no other network calls: no analytics, no advertising, no
telemetry, no third-party trackers.

## Data you can delete

**Settings → Data → Delete all data** deletes everything Muviss keeps on the
device — your library, watch history, lists, triage decisions, profile and
settings — without uninstalling. Uninstalling does the same. Either way there
is nothing left on any server, because nothing you enter is ever sent to one.
Settings → Data → Export saves a copy first if you might want it back.

## Contact

Muviss is developed by Juanje Cilla. Open an issue on the project's GitHub
repository for privacy questions.

## Changes to this policy

This file is versioned alongside the app's source in the repository; the
"last updated" date above always reflects the latest revision.
