# Muted Genres

TMDB's popularity-ordered `/discover` — the source of the triage deck and of Discover's "Popular now" — is full of talk and late-night shows (live check, 2026-10-17: five of the first twenty TV results: Watch What Happens Live, The Tonight Show twice, Late Night with Seth Meyers, The Late Show). Almost nobody tracks them, and a deck is only as good as its first cards.

## Decision

- **A user-chosen set of *Muted Genres*** (CONTEXT.md). Default: TV Talk (10767), News (10763) and Reality (10764), for fresh and existing installs alike. Late-night shows are tagged Talk; SNL and The Daily Show are tagged News, which is why News is in. Any TMDB TV or movie genre can be muted from Settings > Content > Muted genres, and Onboarding offers Talk, News, Reality and Soap with example names and best-effort posters.
- **Per type, not per id.** TMDB numbers movie and TV genres separately, so the key is `(type, id)`.
- **Any muted genre hides a title** (Fallon is Comedy + Talk, and hiding only when *all* genres are muted would hide nothing). **No genres means never hidden**: award shows come back with `genre_ids: []` and no genre rule can catch them (#386).
- **Server-side where TMDB allows it, client-side everywhere else.** `/discover` takes `without_genres=10767|10763|10764` (`|` is "any of"; checked live), so the deck and Popular are not thinned after the fact — the deck is capped at 500 pages and a page that arrives 25% empty costs requests. Recommendations, similar, For you and typed search have no such parameter, so they are filtered on the `genre_ids` every list row carries. `genre_ids` was not parsed before; `MediaSummary.genreIds` is now, defaulted.
- **Explicit beats standing.** Picking a genre chip shows that genre even if it is muted (other muted genres still apply inside it). A due Snooze re-enters the deck regardless — the user postponed it on purpose, and it carries no genres anyway. A title opened on Detail is never filtered.
- **Typed search is not muted unless the user opts in** ("Also hide in search", off). ADR 0010 said search and the Popular carousels "must keep returning the whole catalogue"; this **amends** that: Popular, the deck, For you and More like this now follow Muted Genres, but looking a show up by name is an explicit act and still finds it.
- **A "Show muted" chip on Triage and Discover lifts muting for the session only.** It is held in memory in one Koin single, so the two screens agree, and a relaunch goes back to Settings. It is hidden while nothing is muted.
- **Per-device, not synced** (#385 records what syncing would take). Stored on the singleton `appSettings` row as `mutedGenres TEXT NOT NULL DEFAULT 'tv:10767:Talk;tv:10763:News;tv:10764:Reality'` plus `mutedGenresInSearch`. The column `DEFAULT` is the whole seeding story: `ALTER TABLE ADD COLUMN ... DEFAULT` gives an existing row the same value with no `UPDATE`, `ensureRow` inherits it, and *Delete all data* (which deletes the row) restores it. An empty string is a real choice, "mute nothing". `MutedGenresCodec`'s test pins that its defaults equal the literal.
- **Muting narrows suggestions only.** It never changes the Library, Progress, Watch Next, widgets or notifications.

## Consequences

- **Names are stored with the ids** so Settings lists what is muted offline, and so a muted genre keeps its name in the language it was muted in (#387).
- **A first load waits for the first muting** (Triage and Search both hold their first fill until `observeMuting()` has emitted), so nothing is loaded unmuted and then replaced — the flash would show exactly the shows the user muted. Any later change reloads from page 1.
- **`MetadataProvider.discover` and `SearchRepository.discover` gained a defaulted `excludedGenreIds`**; the hand-written fakes had to take the parameter anyway, since an override cannot omit it.
- `trending()` (no UI uses it today) also returns `genre_ids` and has no `without_genres`; a surface that adopts it must filter client-side.
- Hidden titles thin client-filtered pages (search with the opt-in, For you); the deck and Popular are filtered server-side so they are not.
- The migration number is whatever is free when this merges: `18.sqm` after EPIC 47's `17.sqm`.
