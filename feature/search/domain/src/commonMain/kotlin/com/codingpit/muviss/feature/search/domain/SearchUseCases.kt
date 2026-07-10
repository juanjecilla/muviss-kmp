package com.codingpit.muviss.feature.search.domain

import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.PagedResult
import com.codingpit.muviss.models.WatchProviders

/** Searches media by free-text query, one page at a time. Blank queries resolve to an empty page. */
class SearchMediaUseCase(private val repository: SearchRepository) {
    suspend operator fun invoke(query: String, page: Int = 1): Result<PagedResult<MediaSummary>> = if (query.isBlank()) {
        Result.success(PagedResult(emptyList(), page = 1, totalPages = 1))
    } else {
        repository.search(query.trim(), page)
    }
}

/** Loads the trending titles; unused by the Search tab's own UI today, kept for other surfaces to reuse. */
class TrendingUseCase(private val repository: SearchRepository) {
    suspend operator fun invoke(): Result<List<MediaSummary>> = repository.trending()
}

/** Loads full detail (incl. seasons/episodes for TV) for one title. */
class MediaDetailUseCase(private val repository: SearchRepository) {
    suspend operator fun invoke(id: MediaId): Result<MediaDetails> = repository.details(id)
}

/** Popularity-sorted browse backing the Search tab's discover carousels and genre drill-down. */
class DiscoverMediaUseCase(private val repository: SearchRepository) {
    suspend operator fun invoke(type: MediaType, page: Int = 1, genreId: String? = null): Result<PagedResult<MediaSummary>> = repository.discover(type, page, genreId)
}

/** Loads the localized genre list backing the discover chip rows. */
class GenresUseCase(private val repository: SearchRepository) {
    suspend operator fun invoke(type: MediaType): Result<List<Genre>> = repository.genres(type)
}

/** Loads where a title can be streamed/rented/bought, for the Detail screen's "Where to watch" section. */
class WatchProvidersUseCase(private val repository: SearchRepository) {
    suspend operator fun invoke(id: MediaId): Result<WatchProviders> = repository.watchProviders(id)
}
