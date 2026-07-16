package com.codingpit.muviss.feature.settings.domain

import com.codingpit.muviss.models.MediaType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Trakt's export/backup JSON — the shape Trakt's own public sync API returns
 * from `/sync/history/movies`, `/sync/history/shows` and `/sync/ratings/...`
 * (unchanged for years, the most stable, documented Trakt shape to target;
 * community backup tools like `traktexport` bundle the same rows under a
 * wrapping object). Documented supported subset (see `docs/IMPORT.md`):
 *
 * A **history** entry (one watch event):
 * ```json
 * {
 *   "watched_at": "2020-01-01T00:00:00.000Z",
 *   "type": "movie",
 *   "movie": { "title": "Poor Things", "year": 2023, "ids": { "imdb": "tt14230458", "tmdb": 792307 } }
 * }
 * ```
 * or, for an episode:
 * ```json
 * {
 *   "watched_at": "2020-01-01T00:00:00.000Z",
 *   "type": "episode",
 *   "episode": { "season": 1, "number": 1, "ids": { "tmdb": 63056 } },
 *   "show": { "title": "Severance", "ids": { "imdb": "tt11280740", "tmdb": 95396 } }
 * }
 * ```
 * A **rating** entry (`/sync/ratings/...`) swaps `watched_at`+`type: episode`
 * for `rated_at`+`rating` (1-10), and can rate a `movie` or a whole `show`
 * (no `episode` object):
 * ```json
 * { "rated_at": "...", "rating": 8, "type": "movie", "movie": { "...": "..." } }
 * ```
 *
 * The parsed file's top level is either a bare JSON array of entries (read
 * as history), or an object `{ "history": [...], "ratings": [...] }` — both
 * arrays hold entries in the same per-entry shape above.
 */
class TraktImportParser : ImportParser {
    override val source: ImportSource = ImportSource.TRAKT

    private val json = Json { ignoreUnknownKeys = true }

    override fun parse(content: String): ImportPayload {
        val trimmed = content.trim()
        val entries = decodeEntries(trimmed) ?: return ImportPayload(source, emptyList())

        val accumulator = ImportTitleAccumulator()
        var skipped = 0
        entries.forEach { entry ->
            val parsed = entry.toImportedTitle()
            if (parsed == null) {
                skipped++
            } else {
                val (key, title) = parsed
                accumulator.add(key, title)
            }
        }

        return ImportPayload(source, accumulator.build(), skipped)
    }

    // Malformed JSON surfaces as either SerializationException or IllegalArgumentException
    // depending on where kotlinx.serialization's decoder gives up; runCatching's Throwable
    // catch covers both without duplicating this block per exception type.
    private fun decodeEntries(trimmed: String): List<TraktEntryDto>? = runCatching {
        when {
            trimmed.startsWith("[") -> json.decodeFromString<List<TraktEntryDto>>(trimmed)

            trimmed.startsWith("{") -> {
                val export = json.decodeFromString<TraktExportDto>(trimmed)
                export.history + export.ratings
            }

            else -> null
        }
    }.getOrNull()

    private fun TraktEntryDto.toImportedTitle(): Pair<String, ImportedTitle>? {
        val ep = episode
        val showDto = show
        return when {
            ep != null && showDto != null -> episodeEntry(ep, showDto)
            type == "movie" && movie != null -> movieEntry(movie)
            type == "show" && showDto != null -> showRatingEntry(showDto)
            else -> null
        }
    }

    private fun TraktEntryDto.episodeEntry(ep: TraktEpisodeDto, showDto: TraktShowDto): Pair<String, ImportedTitle>? {
        val seasonNumber = ep.season ?: return null
        val episodeNumber = ep.number ?: return null
        val ref = ExternalTitleRef(imdbId = showDto.ids.imdb, tmdbId = showDto.ids.tmdb?.toString())
        val title = ImportedTitle(
            externalRef = ref,
            type = MediaType.TV,
            displayTitle = showDto.title,
            rating = rating,
            episodes = listOf(ImportedEpisode(seasonNumber, episodeNumber)),
        )
        return ImportTitleAccumulator.key(ref, MediaType.TV, showDto.title) to title
    }

    private fun TraktEntryDto.movieEntry(movieDto: TraktMovieDto): Pair<String, ImportedTitle> {
        val ref = ExternalTitleRef(imdbId = movieDto.ids.imdb, tmdbId = movieDto.ids.tmdb?.toString())
        val title = ImportedTitle(
            externalRef = ref,
            type = MediaType.MOVIE,
            displayTitle = movieDto.title,
            rating = rating,
            watched = watchedAt != null,
        )
        return ImportTitleAccumulator.key(ref, MediaType.MOVIE, movieDto.title) to title
    }

    private fun TraktEntryDto.showRatingEntry(showDto: TraktShowDto): Pair<String, ImportedTitle> {
        val ref = ExternalTitleRef(imdbId = showDto.ids.imdb, tmdbId = showDto.ids.tmdb?.toString())
        val title = ImportedTitle(externalRef = ref, type = MediaType.TV, displayTitle = showDto.title, rating = rating)
        return ImportTitleAccumulator.key(ref, MediaType.TV, showDto.title) to title
    }
}

@Serializable
internal data class TraktIdsDto(
    val trakt: Long? = null,
    val imdb: String? = null,
    val tmdb: Long? = null,
)

@Serializable
internal data class TraktMovieDto(
    val title: String = "",
    val year: Int? = null,
    val ids: TraktIdsDto = TraktIdsDto(),
)

@Serializable
internal data class TraktShowDto(
    val title: String = "",
    val year: Int? = null,
    val ids: TraktIdsDto = TraktIdsDto(),
)

@Serializable
internal data class TraktEpisodeDto(
    val season: Int? = null,
    val number: Int? = null,
    val title: String? = null,
    val ids: TraktIdsDto = TraktIdsDto(),
)

@Serializable
internal data class TraktEntryDto(
    val type: String? = null,
    @SerialName("watched_at") val watchedAt: String? = null,
    @SerialName("rated_at") val ratedAt: String? = null,
    val rating: Int? = null,
    val movie: TraktMovieDto? = null,
    val show: TraktShowDto? = null,
    val episode: TraktEpisodeDto? = null,
)

@Serializable
internal data class TraktExportDto(
    val history: List<TraktEntryDto> = emptyList(),
    val ratings: List<TraktEntryDto> = emptyList(),
)
