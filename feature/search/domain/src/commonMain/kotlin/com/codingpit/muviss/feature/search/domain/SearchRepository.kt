package com.codingpit.muviss.feature.search.domain

import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.PagedResult
import com.codingpit.muviss.models.WatchProviders

/**
 * Domain-owned contract for discovering media. The implementation lives in the
 * data layer and delegates to whichever
 * [MetadataProvider][com.codingpit.muviss.core.network.MetadataProvider] owns
 * the requested source.
 */
interface SearchRepository {
    suspend fun search(query: String, page: Int = 1): Result<PagedResult<MediaSummary>>

    suspend fun trending(): Result<List<MediaSummary>>

    suspend fun details(id: MediaId): Result<MediaDetails>

    /** Popularity-sorted browse for [type], optionally narrowed to one of [genres]' ids. */
    suspend fun discover(type: MediaType, page: Int = 1, genreId: String? = null): Result<PagedResult<MediaSummary>>

    /** Localized genre list for [type], used to populate the discover chip rows. */
    suspend fun genres(type: MediaType): Result<List<Genre>>

    /** Streaming/rent/buy availability for [id] in the configured region (see [com.codingpit.muviss.core.network.MetadataLocale]). */
    suspend fun watchProviders(id: MediaId): Result<WatchProviders>
}
