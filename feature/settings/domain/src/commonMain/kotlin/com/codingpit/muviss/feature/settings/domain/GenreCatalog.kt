package com.codingpit.muviss.feature.settings.domain

import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType

/** The genres a source offers for [MediaType], so Settings can list what is mutable. Needs the network. */
interface GenreCatalog {
    suspend fun genres(type: MediaType): Result<List<Genre>>

    /** Popular titles of one genre, most popular first, for illustrating what muting it hides. */
    suspend fun examples(type: MediaType, genreId: String, limit: Int): Result<List<MediaSummary>>
}

class LoadGenresUseCase(private val catalog: GenreCatalog) {
    suspend operator fun invoke(type: MediaType): Result<List<Genre>> = catalog.genres(type)
}
