package com.codingpit.muviss.feature.settings.domain

import com.codingpit.muviss.models.MediaType

/**
 * Muviss's own simple CSV import format — the escape hatch for a tracker
 * this app has no dedicated parser for, filled in by hand or exported from a
 * spreadsheet. Documented in `docs/IMPORT.md`; the shape in one sentence:
 * one row per movie/whole-show entry OR one row per watched TV episode,
 * columns identified by header name (order doesn't matter):
 *
 * ```
 * title,type,imdb_id,tmdb_id,rating,watched,season,episode
 * Poor Things,movie,tt14230458,792307,8,true,,
 * The Bear,tv,tt14452776,,,,1,1
 * The Bear,tv,tt14452776,,,,1,2
 * ```
 *
 * - `title` and `type` (`movie`/`tv`) are required; every other column is
 *   optional.
 * - At least one of `imdb_id`/`tmdb_id` is needed to resolve the row — a row
 *   with neither is still parsed (so it's added to a preview's counts) but
 *   will be unresolved.
 * - `season`+`episode` present together (both integers) mark that specific
 *   episode watched — one row per episode, all sharing the same title/ids.
 * - Without `season`/`episode`, the row is title-level: `rating` still
 *   applies, and for a movie, `watched` (`1`/`true`/`yes`, case-insensitive;
 *   anything else — including blank — counts as not watched) toggles the
 *   seen flag. A title-level TV row only adds the show to the library; this
 *   format has no shorthand for "mark the whole show watched" (see
 *   `docs/IMPORT.md` for why), so per-episode rows are required for TV
 *   progress.
 * - A row missing `title` or `type`, or whose `type` isn't `movie`/`tv`,
 *   is skipped and counted in [ImportPayload.skippedRowCount].
 */
class GenericCsvImportParser : ImportParser {
    override val source: ImportSource = ImportSource.GENERIC_CSV

    override fun parse(content: String): ImportPayload {
        val table = CsvTable.from(content) ?: return ImportPayload(source, emptyList())
        val accumulator = ImportTitleAccumulator()
        var skipped = 0

        table.rows.forEach { row ->
            val parsed = parseRow(table, row)
            if (parsed == null) {
                skipped++
            } else {
                val (key, title) = parsed
                accumulator.add(key, title)
            }
        }

        return ImportPayload(source, accumulator.build(), skipped)
    }

    private fun parseRow(table: CsvTable, row: List<String>): Pair<String, ImportedTitle>? {
        val displayTitle = table.value(row, "title") ?: return null
        val type = table.value(row, "type")?.let(::parseType) ?: return null
        val ref = ExternalTitleRef(imdbId = table.value(row, "imdb_id"), tmdbId = table.value(row, "tmdb_id"))
        val rating = table.value(row, "rating")?.toIntOrNull()
        val season = table.value(row, "season")?.toIntOrNull()
        val episode = table.value(row, "episode")?.toIntOrNull()
        val watchedColumn = table.value(row, "watched")?.toLenientBoolean() ?: true

        val imported = if (season != null && episode != null) {
            ImportedTitle(
                externalRef = ref,
                type = type,
                displayTitle = displayTitle,
                rating = rating,
                episodes = if (watchedColumn) listOf(ImportedEpisode(season, episode)) else emptyList(),
            )
        } else {
            ImportedTitle(
                externalRef = ref,
                type = type,
                displayTitle = displayTitle,
                rating = rating,
                watched = type == MediaType.MOVIE && watchedColumn,
            )
        }

        return ImportTitleAccumulator.key(ref, type, displayTitle) to imported
    }

    private fun parseType(raw: String): MediaType? = when (raw.trim().lowercase()) {
        "movie" -> MediaType.MOVIE
        "tv", "show", "series" -> MediaType.TV
        else -> null
    }
}

/** `1`/`true`/`yes`/`y` (case-insensitive) are truthy; everything else — including blank — is false. */
internal fun String.toLenientBoolean(): Boolean = trim().lowercase() in setOf("1", "true", "yes", "y")
