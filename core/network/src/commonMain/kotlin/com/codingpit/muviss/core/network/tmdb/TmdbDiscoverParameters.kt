package com.codingpit.muviss.core.network.tmdb

import com.codingpit.muviss.core.common.civilDateOf
import com.codingpit.muviss.core.network.DiscoverFilters
import com.codingpit.muviss.models.MediaType
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.parameter

/**
 * Writes [filters] onto a `/discover/{movie|tv}` request. `|` is "any of these" in TMDB's filter
 * syntax (verified live for `without_genres`, 2026-10-17). The release-date parameters differ by type
 * (`primary_release_date` for films, `first_air_date` for shows), and certification is a film-only
 * filter. A watch provider without a region does nothing on TMDB, so neither is sent.
 *
 * Not verified against the live API: `certification` with several ratings joined by `|`, and
 * `with_runtime` on shows. Both are tracked on the Next Pick engine's live check.
 */
internal fun HttpRequestBuilder.applyDiscoverFilters(type: MediaType, filters: DiscoverFilters) {
    filters.genreIds.takeIf { it.isNotEmpty() }?.let { parameter("with_genres", it.joinToString("|")) }
    // An explicit pick is never also excluded.
    (filters.excludedGenreIds - filters.genreIds).takeIf { it.isNotEmpty() }?.let { parameter("without_genres", it.joinToString("|")) }
    filters.minRuntimeMinutes?.let { parameter("with_runtime.gte", it) }
    filters.maxRuntimeMinutes?.let { parameter("with_runtime.lte", it) }
    filters.originalLanguage?.takeIf { it.isNotBlank() }?.let { parameter("with_original_language", it) }
    val dateKey = if (type == MediaType.MOVIE) "primary_release_date" else "first_air_date"
    filters.releasedOnOrAfterEpochDay?.let { parameter("$dateKey.gte", isoDate(it)) }
    filters.releasedOnOrBeforeEpochDay?.let { parameter("$dateKey.lte", isoDate(it)) }
    val region = filters.watchRegion?.takeIf { it.isNotBlank() }
    if (region != null && filters.watchProviderIds.isNotEmpty()) {
        parameter("watch_region", region)
        parameter("with_watch_providers", filters.watchProviderIds.joinToString("|"))
    }
    if (type == MediaType.MOVIE && filters.certifications.isNotEmpty() && !filters.certificationCountry.isNullOrBlank()) {
        parameter("certification_country", filters.certificationCountry)
        parameter("certification", filters.certifications.joinToString("|"))
    }
    filters.minVoteCount?.let { parameter("vote_count.gte", it) }
}

private fun isoDate(epochDay: Long): String {
    val date = civilDateOf(epochDay)
    return "${date.year.toString().padStart(YEAR_DIGITS, '0')}-${date.month.toString().padStart(2, '0')}-${date.day.toString().padStart(2, '0')}"
}

private const val YEAR_DIGITS = 4
