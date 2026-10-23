# Privacy Policy — Muviss

_Last updated: 2026-10-22._

Muviss is a personal, offline-first tracker for movies and TV shows. There are
no user accounts, no social features, and no analytics/advertising SDKs. This
document explains the only ways Muviss talks to the network, and exactly
what leaves your device.

## What Muviss stores

Everything you track — your collection, favorites, and per-episode watch
progress — is stored **only on your device**, in a local SQLite database (see
ADR 0002, "offline-first, local source of truth"). Muviss has no backend of
its own and no account system; nobody but you (and whoever has your device,
or, if you turn on Device Backup below, access to that backup) can see this
data.

**Device Backup (optional, off by default).** Your phone's own backup can
include the library, so a new phone picks up where you left off: iCloud or a
device backup on iOS, and Google's backup or a device-to-device transfer on
Android. It is off until you turn it on — in the first-run introduction or in
Settings → Data — and it is not shown on platforms where the OS has no such
backup to offer. When it is on, the copy goes wherever that backup goes, as
every other app's data does: iCloud or Google's backup under your own account,
a computer you back your iPhone up to, or the new phone in a device-to-device
transfer. Muviss has no server to receive it and never sees it. When it is
off, the library is left out of those backups and transfers. Either way the
copy is yours, not Muviss's.

## Network calls Muviss makes

1. **TMDB (The Movie Database) API** — [themoviedb.org](https://www.themoviedb.org).
   Used to search for titles and fetch metadata: poster art, synopsis,
   seasons/episodes, release dates. Requests include only the search
   query/title id you're looking up and Muviss's API key — never anything
   that identifies you personally. See [TMDB's own privacy
   policy](https://www.themoviedb.org/privacy-policy) for how they handle
   that traffic.

   This product uses the TMDB API but is not endorsed or certified by TMDB.

   **Your own TMDB key (optional).** Where the app offers it, Settings → TMDB
   data source lets you use your own TMDB credential instead of Muviss's, so
   your requests are made under your own TMDB account. The key is stored only
   on your device, encrypted with a key held by the platform (Android Keystore,
   iOS Keychain, the desktop's OS secret store, or the browser's WebCrypto),
   and is sent only to TMDB. It is never synced, never included in a data
   export, and removed by **Delete all data**. It is also excluded from
   Android's backups and device transfers, whatever the Device Backup switch
   says, and scrubbed from crash reports.

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

3. **ConfigCat (remote configuration)** — optional, and only on a build that
   has a ConfigCat key configured (on a build without one, such as every
   developer build, nothing is fetched). Muviss downloads a small settings file
   from ConfigCat's servers (EU data governance) when the app starts or returns
   to the foreground, at most once an hour. It holds values Muviss can change
   after release — whether an optional feature is offered, and a replacement
   TMDB credential if the one built into the app is ever revoked. **Nothing
   about you is sent**: no identifier, no library, no settings; the values are
   evaluated on your device. The request does expose your **IP address** to
   ConfigCat, as any request to a server does, and it is not stored by Muviss.
   The last file received is kept in the local database so the app works
   offline; **Delete all data** removes it. See
   [ConfigCat's privacy policy](https://configcat.com/privacy/).

**Feature requests and bug reports.** Settings → About → **Request a feature**
and **Report a problem** each open a form in your browser, hosted by [Tally](https://tally.so/help/privacy-policy) (or by
[Typeform](https://www.typeform.com/privacy-policy/) if we ever switch
provider; the row opens whichever one is set up). Muviss itself sends nothing:
the link is opened by your browser, and what you write (and the email address,
if you choose to give one so we can reply) goes to the form provider and to us,
never through the app. The link carries up to four values so a request can be
understood: the **app version**, the **platform** (Android, iOS, desktop or
web), the **operating system version** where the app can read it (on the web
it cannot, so that one is left out) and the **device language and region** (for example `es-ES`). It carries no
identifier, nothing about your library and nothing about your other settings. The
provider receives those values as soon as the page loads, whether or not you
go on to submit anything: Tally's server gets them with the request for the
page, and Typeform's page reads them from the link and sends them to Typeform
while it loads; closing the page without submitting means no
response is sent. The rows appear only when a form has been set up. The text
of a feature request or bug report may be copied into the project's public
issue tracker so it can be planned or triaged, **never with your email
address**; leave anything private out of it.

Muviss makes no other network calls: no analytics, no advertising, no
telemetry, no third-party trackers.

## Data you can delete

**Settings → Data → Delete all data** deletes everything Muviss keeps on the
device — your library, watch history, lists, triage decisions, profile and
settings, Device Backup included (it goes back to off) — without uninstalling.
Uninstalling removes the same, with one exception on iPhone: an Own TMDB Key
you saved sits in the iOS Keychain, which can outlive the app, so use Delete
all data first if you want the key gone too. Either way nothing is left on a Muviss server,
because Muviss never sends what you enter to one. A copy your phone's own
backup already holds is outside Muviss and is not touched by either; delete it
where that backup lives (the phone's backup settings for iCloud or Google, or
the computer that holds an iPhone backup).
(A feature request or bug report you submit through a form is another
exception: it lives outside the app, so deleting data in Muviss cannot remove it. It can
exist in up to three places: with the form provider, in the notification email
the provider sends us, and, if it was copied there, in a public issue (its
text only, never your email address). Ask us through the contact below and we
will delete all three: the response at the provider, the email, and the issue
or the copied text in it. The
issue tracker is public, so do not put your email address or the text of the
request or report in that issue: say only that you want one deleted, and we will
reply there with a private way to identify it.)
Settings → Data → Export saves a copy first if you might want it back.

## Contact

Muviss is developed by Juanje Cilla. How to reach the developer, privately by
email or through the in-app forms, is on the [support page](https://muvissapp.com/support/).
For a question about your data, or to ask for something you sent through a form
to be deleted, use the email address there rather than a public GitHub issue.
The project's GitHub repository also takes issues for anything that is fine to
say in public.

## Changes to this policy

This file is versioned alongside the app's source in the repository; the
"last updated" date above always reflects the latest revision.
