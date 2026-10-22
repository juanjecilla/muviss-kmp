package com.codingpit.muviss.core.network

/** How TMDB orders a discover page. */
enum class DiscoverSort(val tmdbValue: String) {
    POPULARITY("popularity.desc"),
    RATING("vote_average.desc"),
    NEWEST_FIRST("primary_release_date.desc"),
}

/**
 * Everything a Next Pick session can ask `/discover` for (EPIC 51, ADR 0032). Plain data; every field
 * defaults to "no constraint", so an empty [DiscoverFilters] is today's popular-titles browse.
 *
 * Dates are epoch days, like everywhere else in the app (there is no date-time library); the provider
 * formats them the way TMDB wants. Not every filter exists for both types: TMDB has no certification
 * filter for shows, and the provider leaves it out rather than guess (see [certifications]).
 */
data class DiscoverFilters(
    /** Titles in *any* of these genres (TMDB's `|`). */
    val genreIds: Set<String> = emptySet(),
    /** Left out server-side, so a page is not thinned after the fact. An id also in [genreIds] is ignored: choosing a genre beats muting it. */
    val excludedGenreIds: Set<String> = emptySet(),
    val minRuntimeMinutes: Int? = null,
    val maxRuntimeMinutes: Int? = null,
    /** An ISO 639-1 code, matched against the title's original language. */
    val originalLanguage: String? = null,
    /** First release (a film) or first air date (a show), inclusive. */
    val releasedOnOrAfterEpochDay: Long? = null,
    val releasedOnOrBeforeEpochDay: Long? = null,
    /** Streaming providers, TMDB ids, any of. Needs [watchRegion]; without it TMDB ignores them, so the provider sends neither. */
    val watchProviderIds: Set<Int> = emptySet(),
    val watchRegion: String? = null,
    /** Films only: `certification_country` plus these ratings, any of. Shows have no such filter on TMDB. */
    val certificationCountry: String? = null,
    val certifications: Set<String> = emptySet(),
    /** Drops titles with too few votes to mean anything. */
    val minVoteCount: Int? = null,
    val sort: DiscoverSort = DiscoverSort.POPULARITY,
)
