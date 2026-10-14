# Importing from another tracker

EPIC 18. Settings > Import from another tracker: pick a file, preview what it
contains, confirm, and it's applied to your library. This document is the
source of truth for exactly what each format supports — the parsers
(`feature/settings/domain`'s `TraktImportParser` / `TvTimeImportParser` /
`GenericCsvImportParser`) implement precisely this, no more.

## Why "documented subset" at all

Trakt's export shape and TV Time's GDPR takeout are both real, but neither
vendor publishes a versioned schema Muviss can code against with certainty.
Rather than guess at edge cases, each parser below targets a deliberately
narrow, explicit subset of the real format and skips (never guesses at) rows
it doesn't recognize — the parse result always reports how many rows were
skipped, and the preview screen shows it before you commit to anything.

## Supported formats

### Trakt export (JSON)

Targets the shape Trakt's own public sync API returns from
`/sync/history/movies`, `/sync/history/shows`, and `/sync/ratings/...` — the
most stable, longest-documented Trakt shape, and the one community backup
tools (e.g. `traktexport`) bundle under a wrapping object.

The file's top level is either:
- a bare JSON array of entries (read as watch history), or
- an object `{ "history": [...], "ratings": [...] }` (both arrays hold
  entries in the same per-entry shape).

A **history** entry (one watch event) for a movie:

```json
{
  "watched_at": "2020-01-01T00:00:00.000Z",
  "type": "movie",
  "movie": { "title": "Poor Things", "year": 2023, "ids": { "imdb": "tt14230458", "tmdb": 792307 } }
}
```

...or for an episode:

```json
{
  "watched_at": "2020-01-01T00:00:00.000Z",
  "type": "episode",
  "episode": { "season": 1, "number": 1, "ids": { "tmdb": 63056 } },
  "show": { "title": "Severance", "ids": { "imdb": "tt11280740", "tmdb": 95396 } }
}
```

A **rating** entry swaps `watched_at`/`type: episode` for `rated_at`+`rating`
(1-10), and can rate a `movie` or a whole `show` (no `episode` object):

```json
{ "rated_at": "2020-01-02T00:00:00.000Z", "rating": 8, "type": "movie", "movie": { "...": "..." } }
```

Rules:
- A rating merges onto the same title as its history entries (matched by
  IMDb/TMDB id, falling back to title text when neither id is present).
- A movie is marked watched only if a **history** entry set `watched_at`; a
  rating alone doesn't imply "watched" (you might rate something you saw
  elsewhere).
- An episode entry missing `season` or `number`, or an entry with none of
  `movie`/`show`+`episode`/`show`-only rating shape, is skipped and counted.
- Malformed JSON fails the whole file (reported as an error, not a partial
  import) — a truncated/corrupted export can't be safely partially trusted.

### TV Time export (CSV)

TV Time's "download my data" GDPR export is a zip of several CSVs (per
community-documented takeout naming, e.g. `tracking-prod-episodes-export.csv`
/ `tracking-prod-movies-export.csv`). TV Time publishes no official schema,
so this is a best-effort, explicitly documented subset — pick **one file at a
time** from the unzipped export; Muviss doesn't unpack the zip itself.

Episodes file — one row per watched episode:

```csv
series_name,tmdb_id,imdb_id,season_number,episode_number,watched_at
Severance,95396,tt11280740,1,1,2022-02-18
```

`show_name`/`show_tmdb_id`/`show_imdb_id` are accepted as column aliases.

Movies file — one row per watched/rated movie:

```csv
movie_name,tmdb_id,imdb_id,rating,watched_at
Poor Things,792307,tt14230458,8,2024-03-01
```

`name` is accepted as an alias for `movie_name`.

Column lookup is by header name (case-insensitive), not position, and the
importer auto-detects episodes vs. movies files (and TV Time vs. the generic
format below) from which columns are present — see `ImportFormatDetector`.

### Generic CSV (Muviss's own format)

The escape hatch for a tracker with no dedicated parser — filled in by hand
or exported from a spreadsheet.

```csv
title,type,imdb_id,tmdb_id,rating,watched,season,episode
Poor Things,movie,tt14230458,792307,8,true,,
The Bear,tv,tt14452776,,,,1,1
The Bear,tv,tt14452776,,,,1,2
```

- `title` and `type` (`movie`/`tv`) are required; every other column is
  optional.
- At least one of `imdb_id`/`tmdb_id` is needed to resolve the row — a row
  with neither is still parsed (counted in the preview) but reported
  unresolved.
- `season`+`episode` present together (both integers) mark that specific
  episode watched — one row per episode, repeating the same title/ids.
- Without `season`/`episode`, the row is title-level: `rating` still
  applies, and for a movie, `watched` (`1`/`true`/`yes`/`y`,
  case-insensitive; anything else, including blank, means not watched)
  toggles the seen flag.
- A title-level TV row (no `season`/`episode`) only adds the show to the
  library — this format has no shorthand for "mark the whole show watched".
  That's deliberate: without fetching the show's full episode list first (a
  network call this app doesn't want to make speculatively during parsing,
  which is pure/offline), there's no way to know which episodes "all of it"
  even means. Per-episode rows are required for TV progress.
- A row missing `title` or `type`, or with an unrecognized `type`, is
  skipped and counted.

### Muviss backup (JSON)

The file Settings > Export writes (`muviss-backup-YYYY-MM-DD.json`) is a
backup, not an import format: it is **restored**, not parsed and resolved
(EPIC 29, #72). `ImportFormatDetector.isMuvissBackup` recognises it by
`exportedAtEpochMs`, which every version carries and no Trakt export does.
`formatVersion` 2 added everything v1 dropped (ratings, notes, lists,
snoozes, profile); a v1 file has no version and restores as 1.

Restoring needs no network: every row merges by `updatedAtEpochMs`, last
write wins, so anything changed on the device since the backup was made is
kept, and restoring the same file twice changes nothing. A backup from a
newer app version is refused. See `SqlDelightBackupRestorer`.

## Id mapping

A row's IMDb/TMDB id becomes a real `MediaId` (`tmdb:movie:603`,
ADR 0006) via `ExternalIdResolver`:

- A TMDB id maps **directly** — no network call, `type` just picks the
  movie/tv suffix (`MediaId.tmdbMovie`/`tmdbTv`).
- An IMDb id goes through TMDB's `/find/{imdb_id}?external_source=imdb_id`
  (`MetadataProvider.findByExternalId`).
- A row with neither id, or a TMDB id with no `type` to disambiguate the
  suffix, is **unresolved** — reported in the preview and the final summary
  with a reason, never silently dropped and never guessed at by matching on
  title text alone.

## Idempotency

Re-running an import on the same file (or a file with overlapping titles)
must not duplicate or regress anything already in your library. The rule:
**existing entries keep their data; only missing pieces are added.**

Concretely:
- **Collection membership** — `CollectionApi.add` upserts (see
  `SqlDelightCollectionRepository.upsertSnapshot`): a title already in your
  library isn't duplicated, and its favorite/rating/note/added-date are
  preserved (only the denormalized display snapshot refreshes).
- **Episode/movie ticks** — `ProgressApi.setEpisodeSeen`/`setMovieWatched`
  upsert a `seen = true` row; ticking an already-seen episode again is a
  no-op in effect. The importer never sends `seen = false` — it only adds
  ticks, never removes one a previous import or your own manual tracking
  already set.
- **Ratings** — only applied when the title **has no rating yet**
  (`ApplyImportUseCase.applyRating`). If you already rated something — by
  hand, or from a previous import — a later import's rating for the same
  title is silently skipped rather than overwriting your edit.

This is exercised end to end against a real SQLDelight (JVM) database in
`feature/settings/data`'s `ImportIdempotencyTest`: applying the same preview
twice, and applying a file with a superset of a previous file's episodes,
both leave the collection/progress tables in the same state a single correct
import would.

## Unresolved and failed titles

Two different things can go wrong, and the result summary tells them apart:
- **Unresolved** — the id-mapping step couldn't place the title on TMDB at
  all (no id, or no match). Nothing is written for it.
- **Failed** — the title resolved to a real `MediaId`, but fetching its full
  TMDB details (needed for the collection snapshot) errored — e.g. a
  transient network failure. One failure doesn't abort the batch; every
  other title still imports (`ApplyImportUseCase` is best-effort per title,
  the same contract `RefreshCollectionSnapshotsUseCase` follows elsewhere).

## Groundwork for future sources (not built)

`MetadataProvider.findByExternalId` and the `ExternalIdResolver` seam are
source-agnostic — resolving directly against Trakt or TVmaze (rather than
bridging through TMDB via IMDb id) only needs a second `MetadataProvider`
implementation registered with `MetadataProviderRegistry` (see ADR 0001).
Neither is built today; TMDB is the only registered source, exactly as
before this EPIC.

## Platform support

`FileImporter` (mirrors EPIC 8's `DataExporter`) picks the file:

| Platform | Mechanism |
|---|---|
| Android | System document picker (`ActivityResultContracts.GetContent`) |
| Desktop (JVM) | `java.awt.FileDialog` in `LOAD` mode |
| Web (JS + Wasm) | A throwaway `<input type=file>` + `FileReader` |
| iOS | **Stub** — `pickFile()` always returns null; see `FileImporter.ios.kt` for what a real `UIDocumentPickerViewController` implementation needs |
