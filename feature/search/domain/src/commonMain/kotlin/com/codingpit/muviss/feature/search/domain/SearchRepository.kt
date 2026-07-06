package com.codingpit.muviss.feature.search.domain

import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary

/**
 * Domain-owned contract for discovering media. The implementation lives in the
 * data layer and delegates to whichever
 * [MetadataProvider][com.codingpit.muviss.core.network.MetadataProvider] owns
 * the requested source.
 */
interface SearchRepository {
    suspend fun search(query: String): Result<List<MediaSummary>>

    suspend fun trending(): Result<List<MediaSummary>>

    suspend fun details(id: MediaId): Result<MediaDetails>
}
