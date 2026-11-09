# Discover opens offline

Issue #724.

## Problem
`SearchViewModel.loadDiscover` makes four calls in order (movie genres, TV genres, popular movies, popular TV); any failure sets `error` and the screen is a full-screen `ErrorState`. Only Ktor's in-memory `HttpCache` exists, gone with the process. Detail already falls back to a stored copy; Discover does not.

## Proposal
- **`discoverCache`** table: section key, TMDB language, raw JSON, `fetchedAtEpochMs`. A cache, not user data, same family as `titleWatchProviders`, `episode` and `remoteConfigCache`: no `isDirty`/`updatedAt`/soft delete; absent from `SyncChangeSet`, the export and a restore; wiped by Delete all data. Migration number claimed at merge.
- **Raw and pre-mute.** Store what TMDB returned, including both genre lists (a genre failure fails the screen today), and apply Muted Genres when reading. A mute change then re-filters cached data instead of showing stale content. The cache fetch does not send `without_genres`.
- **Read path**: emit cached sections immediately; revalidate in the background; on success replace the row and the UI; on failure keep that section's cache. A banner "Offline · showing saved" appears only when the data is older than its TTL and the refresh failed. Nothing cached and offline keeps today's error state.
- **TTL**: show any age while offline; refresh when older than 6 h (popularity moves daily).
- **For you** is cached too, keyed by its seed set; it already empties quietly on failure.
- Web uses the same database (SQL.js), so it gets this for free.

## Corner cases
Language or region change (key includes language); first run offline; one section fails and three succeed; row growth (one per section, replaced); skeleton must not flash on a cache hit (see `home-skeleton-wave.md`); corrupt JSON after an app update changes the model (decode failure = cache miss, never a crash); `HttpCache` and this cache both answering (the DB read comes first, the network always revalidates).

## Tests
Offline cold start renders cached; per-section failure; mute change re-filters; language change misses; decode failure falls through; `ExportShapeTest`, `SyncChangeSetShapeTest` and the eraser cover the table; migration test with the new fixture. Landing `en.ts`/`es.ts` copy.
