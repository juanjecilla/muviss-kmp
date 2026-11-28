# Sample Lineups

Test data for EPIC 54 (#1132) and EPIC 57 (#1135). Nothing reads these yet, and
they are **not published**: they live outside `website/public/` on purpose.

The format is a proposal. The EPIC 54 JSON Schema becomes the authority; change
these files with it.

## Files

| File | What a correct app does |
|---|---|
| `v1.json` | Renders every shelf that is in its date window and region |
| `sets/criterion-sample.json` | The first Criterion spines (up to 40), sorted by `spine` |
| `edge/unknown-kind.json` | Skips `from-the-future`, renders `still-renders` |
| `edge/invalid-shelves.json` | Skips the shelf without `id`, without `source`, without `personId`, with the impossible date and with the missing set; renders `still-renders` |
| `edge/higher-major.json` | Ignores the whole file and keeps the stored copy |
| `edge/year-wrap.json` | Shows the shelf from 28 December to 5 January, both ends included |
| `edge/too-few-titles.json` | Hides `five-titles` (fewer than 6), renders `still-renders` |

## `v1.json` by date

| Date | Seasonal and dated shelves shown |
|---|---|
| 2026-10-31 | `halloween` |
| 2026-11-28, region US | `thanksgiving`, `person-of-the-week` |
| 2026-11-28, region ES | `person-of-the-week` |
| 2026-12-10 | `christmas`, `christmas-picks` |
| 2027-01-03 | `christmas-picks` |
| 2027-06-01 | none |

`criterion-channel` shows only in US and CA, on any date. Everything else is
always on.

## Fields
- `activeFrom` / `activeUntil`: inclusive. `MM-DD` with `yearly: true`,
  `YYYY-MM-DD` otherwise.
- `source.filters`: named after `DiscoverFilters` (`genreIds`, `originalLanguage`,
  `releasedOnOrAfter`, `releasedOnOrBefore` as `YYYY-MM-DD`, `watchProviderIds`,
  `minVoteCount`), plus `keywordIds`. `sort` is `popularity`, `rating` or
  `newest_first`. `watchRegion` is not in the file: the app uses the person's.
- `setRef`: path relative to the Lineup file.
- A set is `{schemaVersion, items: [{mediaType, tmdbId, spine?}]}`.

## What was checked (2026-11-28)
- Movie, TV and person ids: resolved on Wikidata.
- Keywords 207317, 3335, 4543, collection 10 and list 1: read on themoviedb.org.
- Criterion set: generated from Wikidata (`P12279`, `P4947`).
- **Not checked:** watch provider `258` for the Criterion Channel, and that any of
  the `discover` queries returns enough titles. No TMDB credential was available.

## Trying one
Once the app reads a Lineup, serve this folder and point `LINEUP_URL` in
`local.properties` at it:

```bash
python3 -m http.server 8000 --directory docs/samples/lineup
# Android emulator: LINEUP_URL=http://10.0.2.2:8000/v1.json
```

Plain HTTP needs a debug-only network security exception on Android and iOS.
