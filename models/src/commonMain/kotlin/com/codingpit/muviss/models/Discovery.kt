package com.codingpit.muviss.models

import kotlinx.serialization.Serializable

/** A genre as reported by a source, e.g. TMDB's "Action" (28). Opaque outside that source. */
@Serializable
data class Genre(
    val id: String,
    val name: String,
)

/**
 * One page of a larger result set. [MetadataProvider][com.codingpit.muviss.core.network.MetadataProvider]
 * calls that can return more than a screenful (search, discover) use this so
 * callers can load more without re-fetching what they already have.
 */
@Serializable
data class PagedResult<T>(
    val items: List<T>,
    val page: Int,
    val totalPages: Int,
) {
    val hasMore: Boolean get() = page < totalPages
}

/** A streaming/rent/buy storefront (e.g. Netflix), as surfaced by TMDB/JustWatch. */
@Serializable
data class WatchProvider(
    val id: String,
    val name: String,
    val logoUrl: String? = null,
)

/**
 * Where a title can be watched in one region, split by offer type. TMDB
 * sources this data from JustWatch, whose terms require attribution wherever
 * it renders (see `JUSTWATCH_ATTRIBUTION_TEXT`).
 */
@Serializable
data class WatchProviders(
    val flatrate: List<WatchProvider> = emptyList(),
    val rent: List<WatchProvider> = emptyList(),
    val buy: List<WatchProvider> = emptyList(),
) {
    val isEmpty: Boolean get() = flatrate.isEmpty() && rent.isEmpty() && buy.isEmpty()
}

/** A genre id within one [MediaType]: TMDB numbers movie and TV genres separately, so an id alone is ambiguous. */
@Serializable
data class GenreKey(val type: MediaType, val id: String)

/**
 * Genres the user has muted (CONTEXT.md "Muted Genre"). A title is hidden when **any** of its genres
 * is muted; one with no genre ids is never hidden, because nothing says what it is.
 */
@Serializable
data class MutedGenres(val keys: Set<GenreKey> = emptySet()) {
    fun hides(summary: MediaSummary): Boolean = summary.genreIds.any { GenreKey(summary.type, it) in keys }

    /** The muted ids of [type], for `without_genres`. */
    fun forType(type: MediaType): Set<String> = keys.filter { it.type == type }.mapTo(linkedSetOf()) { it.id }

    /** These, minus an explicitly picked genre: browsing G shows G. */
    fun without(type: MediaType, id: String?): MutedGenres = if (id == null) this else MutedGenres(keys - GenreKey(type, id))

    companion object {
        val NONE = MutedGenres()
    }
}
