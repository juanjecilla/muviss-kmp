# Discover Lineup: served shelves, chip search, Personalization, Criterion

Status: agreed in a grilling session on 2026-11-28. Not built. Vocabulary is in
`CONTEXT.md` (Shelf, Shelf Source, Lineup, Set); the hosting decision is ADR 0040;
the later Supabase option is `docs/proposals/lineup-backend.md`.

Epics: 54 Discover Lineup (#1132), 55 chip search (#1133), 56 Personalization (#1134),
57 Criterion (#1135). See `docs/EPICS.md`.

## Problem
Discover is fixed in `SearchScreen.kt`: For you, movie and TV genre chips,
Popular movies and TV. We want seasonal shelves, editor's picks, a person of the
week and the like, changed without an app release and without hardcoding content.

It is not a "feed": Discover stays the screen's name, and the store listing's
"no feeds" stays true.

## The Lineup (EPIC 54)

```json
{ "schemaVersion": 1, "shelves": [
  { "id": "christmas", "category": "seasonal",
    "title": {"en": "Christmas classics", "es": "Clásicos de Navidad"},
    "activeFrom": "12-01", "activeUntil": "12-26", "yearly": true,
    "source": {"kind": "discover", "mediaType": "movie",
               "filters": {"keywordIds": [207317], "sort": "popularity"}} },
  { "id": "for-you", "category": "forYou",
    "source": {"kind": "builtin", "name": "forYou"} },
  { "id": "criterion", "category": "editorial",
    "title": {"en": "The Criterion Collection"},
    "source": {"kind": "titles", "setRef": "sets/criterion.json", "sort": "spine"} } ] }
```

Sample files, the field names and the expected behaviour are in
`docs/samples/lineup/`. Resolve new keyword ids with TMDB `search/keyword`.

| Source kind | Fields | TMDB |
|---|---|---|
| `builtin` | `forYou`, `movieGenres`, `tvGenres`, `popularMovies`, `popularTv` | as today |
| `titles` | `items[]` or `setRef`, `sort` | summary per visible title |
| `discover` | `mediaType`, `filters` | `discover/{movie,tv}` |
| `person` | `personId`, `department?` | `person/{id}` |
| `tmdbCollection` | `id` | `collection/{id}` |
| `tmdbList` | `id` | `list/{id}` |
| `chart` | `trending_day`, `trending_week`, `top_rated`, `upcoming`, `now_playing`, `airing_today`, `on_the_air` + `mediaType` | chart endpoints |

`discover.filters` maps onto `DiscoverFilters` (`:core:network`), extended with
keywords, companies, networks, cast and crew. TV discover has no person filter.

Not available from TMDB: awards, box office, "born this week".

### Rules
- **Built-in sections are shelves** the Lineup orders. A copy of the Lineup ships
  in the app for a first offline launch. Intro card, Triage entry, What's next
  and the Show-muted chip stay fixed above the shelves. The validator checks the
  bundled copy too.
- **Scheduling** happens on the device: `activeFrom`/`activeUntil`, with
  `yearly: true` for a month-day window that may wrap the year end.
- **Targeting:** `regions` is matched against the provider-region setting (absent
  means everywhere). `title` is per locale, falling back to `en`.
- **Muted Genres always apply**, with no override. A shelf left with fewer than
  6 titles hides.
- **Forward compatibility:** an unknown source kind or an invalid shelf is
  skipped and the rest render. A higher `schemaVersion` keeps the last stored copy.
- **Category** is one of `seasonal`, `editorial`, `spotlight`, `charts`,
  `forYou`, `genres`, `popular`. People switch categories off, not shelves.
- **See all:** a shelf header opens `ShelfRoute(shelfId)`, a paged grid. A person
  shelf's header opens the existing `PersonRoute`.
- **Person shelf** is a Filmography via `FilmographyOrganizer`. Nothing about a
  Person is stored and there is no following.

### Fetch and storage
- URL comes from Remote Config `lineupUrl`. The compiled default is `LINEUP_URL`
  (the public muvissapp.com file; not a secret). `off` or blank means the bundled
  copy only. The compiled default matters because Remote Config is dark until the
  ConfigCat product exists (#316).
- Fetched on foreground, at most once every 6 hours, with ETag.
- Last good copy in a new `lineupCache` table: a cache like `remoteConfigCache`,
  neither synced nor exported.
- Resolved titles go in `discoverCache`, keyed by shelf id and language, muted on
  read. **Check first** whether its current key columns need a schema change, and
  read #759/#835 (muted answers and the offline copy).
- `MetadataProvider.summary(id)`: bare `movie/{id}` or `tv/{id}`, no appended
  seasons, loaded for visible items only. A Lineup holds only
  `{mediaType, tmdbId}`, so nothing in it goes stale.
- The bundled copy cannot assume Compose resources: `muviss.kmp.compose` gives
  each UI module an internal `Res`, so decide which module owns it.
- Migration: take the next free `.sqm` at merge; do not reserve a number.

### Also in the epic
JSON Schema (`website/lineup.schema.json`), `scripts/lineup/validate.sh` (schema,
unique ids, dates, `setRef`s exist, `--live` resolves ids) in CI, the deploy
workflow (ADR 0040), landing copy in en and es, the Play description, and
`docs/PRIVACY.md` (a new request to muvissapp.com carrying no user data).

## Chip search (EPIC 55)
- Typed search returns entities: titles, **People** (`search/multi` persons are
  dropped by the mapper today), Keywords, Collections, Studios
  (`search/person|keyword|collection|company`).
- Tapping an entity makes a chip. Entity chips plus fixed chips (Movie/TV, Genre,
  Decade, Min rating, Original language) build one `discoverFiltered` query.
- A person chip on TV falls back to that Person's Filmography.
- Typed text and chips are separate modes: `/discover` takes no text.
- Muting follows the "Muted genres in search" Display Preference, as typed search
  does. An explicitly chosen genre chip always shows its genre (ADR 0031).
- "On my services" is deferred: there is no list of the person's providers.
- Depends on EPIC 54 only for the shared `DiscoverFilters` fields.

## Personalization (EPIC 56)
- A Settings submenu: Discover category switches, Muted genres, Muted genres in
  search, Taste profile, and the Next Pick group. Triage, language and region,
  and specials stay where they are.
- Category switches are a Display Preference (ADR 0036), so they sync and export:
  next free `.sqm`; the `MuvissDataExport` field **defaulted** so
  `backups/v1-export.json` still parses; `ExportShapeTest` and
  `SyncChangeSetShapeTest`; a `supabase/migrations` change with
  `FakeSupabaseServer` in step; `DisplayPreferencesRestorer`.
- Depends on EPIC 54 for `category`.

## Criterion Collection (EPIC 57)
Criterion is a distributor, so TMDB's `with_companies` (production companies)
misses most of the catalogue.

Wikidata, queried 2026-11-28:

| | count |
|---|---|
| items with spine number (`P12279`), highest spine 1349 | 1269 |
| of those, with TMDB movie id (`P4947`) | 1241 |
| of those, with TMDB TV id (`P4983`) | 11 |
| of those, with neither | 19 |
| items with Criterion film ID (`P9584`) | 1721 |

(The overlap of the movie and TV rows has not been checked.)

- `lineup/sets/criterion.json`: `{tmdbId, mediaType, spine}`, generated by
  `scripts/lineup/criterion.py` straight from Wikidata's TMDB ids (CC0), with
  `P345` + TMDB `find/{id}` only as a fallback. A monthly CI job opens a PR.
- Used by a `titles` shelf with `setRef`, sorted by spine.
- Spine items only. `P9584` is broader (the criterion.com film page) and is not
  the same list. How box sets are recorded is unchecked; look before shipping.
- Plus an "On the Criterion Channel" `discover` shelf by watch provider,
  `regions: [US, CA]`. **Check first** the provider id and regions with
  `watch/providers/movie?watch_region=US`.
- The name is used to describe the catalogue only: no logo, no branding.
- Depends on EPIC 54 (`titles`, `setRef`, `ShelfRoute`).

## Later
"On my services"; Snooze cards via `summary(id)`; a people row
(`trending/person`); studio and network pages; a Supabase-hosted Lineup.
