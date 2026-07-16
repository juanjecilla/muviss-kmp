package com.codingpit.muviss.feature.settings.domain

import com.codingpit.muviss.models.MediaType

/**
 * TV Time's "download my data" GDPR export is a zip of several CSVs (per
 * TV Time community-documented takeout naming, e.g.
 * `tracking-prod-episodes-export.csv` / `tracking-prod-movies-export.csv`);
 * TV Time publishes no official schema, so this parser targets a documented
 * **subset** (see `docs/IMPORT.md`) rather than a guaranteed-exact shape, and
 * sniffs columns by name (case-insensitive, with common aliases) rather than
 * position so minor real-world header variations still parse:
 *
 * Episodes file — one row per watched episode:
 * ```
 * series_name,tmdb_id,imdb_id,season_number,episode_number,watched_at
 * Severance,95396,tt11280740,1,1,2022-02-18
 * ```
 * (`show_name`/`show_tmdb_id`/`show_imdb_id` are also accepted aliases.)
 *
 * Movies file — one row per watched/rated movie:
 * ```
 * movie_name,tmdb_id,imdb_id,rating,watched_at
 * Poor Things,792307,tt14230458,8,2024-03-01
 * ```
 * (`name` is accepted as an alias for `movie_name`.)
 *
 * The user picks one file at a time (TV Time's zip isn't unpacked by this
 * app); [ImportFormatDetector] tells episodes and movies files apart from
 * their header the same way this parser's [parse] does, so either can be
 * dropped into the same import flow.
 */
class TvTimeImportParser : ImportParser {
    override val source: ImportSource = ImportSource.TV_TIME

    override fun parse(content: String): ImportPayload {
        val table = CsvTable.from(content) ?: return ImportPayload(source, emptyList())
        return if (table.hasAnyColumn("season_number", "episode_number")) parseEpisodes(table) else parseMovies(table)
    }

    private fun parseEpisodes(table: CsvTable): ImportPayload {
        val accumulator = ImportTitleAccumulator()
        var skipped = 0

        table.rows.forEach { row ->
            val name = table.value(row, "series_name") ?: table.value(row, "show_name")
            val season = table.value(row, "season_number")?.toIntOrNull()
            val episode = table.value(row, "episode_number")?.toIntOrNull()
            if (name == null || season == null || episode == null) {
                skipped++
                return@forEach
            }
            val ref = ExternalTitleRef(
                imdbId = table.value(row, "imdb_id") ?: table.value(row, "show_imdb_id"),
                tmdbId = table.value(row, "tmdb_id") ?: table.value(row, "show_tmdb_id"),
            )
            val title = ImportedTitle(ref, MediaType.TV, name, episodes = listOf(ImportedEpisode(season, episode)))
            accumulator.add(ImportTitleAccumulator.key(ref, MediaType.TV, name), title)
        }

        return ImportPayload(source, accumulator.build(), skipped)
    }

    private fun parseMovies(table: CsvTable): ImportPayload {
        val accumulator = ImportTitleAccumulator()
        var skipped = 0

        table.rows.forEach { row ->
            val name = table.value(row, "movie_name") ?: table.value(row, "name")
            if (name == null) {
                skipped++
                return@forEach
            }
            val ref = ExternalTitleRef(imdbId = table.value(row, "imdb_id"), tmdbId = table.value(row, "tmdb_id"))
            val rating = table.value(row, "rating")?.toIntOrNull()
            val title = ImportedTitle(ref, MediaType.MOVIE, name, rating = rating, watched = true)
            accumulator.add(ImportTitleAccumulator.key(ref, MediaType.MOVIE, name), title)
        }

        return ImportPayload(source, accumulator.build(), skipped)
    }
}
