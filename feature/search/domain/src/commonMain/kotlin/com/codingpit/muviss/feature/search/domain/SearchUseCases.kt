package com.codingpit.muviss.feature.search.domain

import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary

/** Searches media by free-text query. Blank queries resolve to an empty list. */
class SearchMediaUseCase(private val repository: SearchRepository) {
    suspend operator fun invoke(query: String): Result<List<MediaSummary>> = if (query.isBlank()) Result.success(emptyList()) else repository.search(query.trim())
}

/** Loads the trending titles shown before the user has typed anything. */
class TrendingUseCase(private val repository: SearchRepository) {
    suspend operator fun invoke(): Result<List<MediaSummary>> = repository.trending()
}

/** Loads full detail (incl. seasons/episodes for TV) for one title. */
class MediaDetailUseCase(private val repository: SearchRepository) {
    suspend operator fun invoke(id: MediaId): Result<MediaDetails> = repository.details(id)
}
