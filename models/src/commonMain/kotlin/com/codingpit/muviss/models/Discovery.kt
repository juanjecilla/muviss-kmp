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
