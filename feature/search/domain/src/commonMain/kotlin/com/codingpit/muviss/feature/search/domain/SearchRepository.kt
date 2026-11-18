package com.codingpit.muviss.feature.search.domain

import com.codingpit.muviss.models.EpisodeDetails
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.PagedResult
import com.codingpit.muviss.models.PersonDetails
import com.codingpit.muviss.models.PersonId
import com.codingpit.muviss.models.TitleImages
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

    /**
     * Popularity-sorted browse for [type], optionally narrowed to one of [genres]' ids.
     * [excludedGenreIds] are [type]'s muted genre ids (ADR 0031), left out server-side; one equal to
     * [genreId] is ignored, because browsing a genre shows it.
     */
    suspend fun discover(type: MediaType, page: Int = 1, genreId: String? = null, excludedGenreIds: Set<String> = emptySet(), fresh: Boolean = false): Result<PagedResult<MediaSummary>>

    /** Localized genre list for [type] (`fresh`: see [MetadataProvider.genres]), used to populate the discover chip rows. */
    suspend fun genres(type: MediaType, fresh: Boolean = false): Result<List<Genre>>

    /** Streaming/rent/buy availability for [id] in the configured region (see [com.codingpit.muviss.core.network.MetadataLocale]). */
    suspend fun watchProviders(id: MediaId): Result<WatchProviders>

    /** Titles recommended alongside [id] (EPIC 16's "More like this"), paged. */
    suspend fun recommendations(id: MediaId, page: Int = 1, fresh: Boolean = false): Result<PagedResult<MediaSummary>>

    /** Titles similar to [id], paged — the Detail screen's fallback when [recommendations] is thin. */
    suspend fun similar(id: MediaId, page: Int = 1, fresh: Boolean = false): Result<PagedResult<MediaSummary>>

    /** One episode's full detail — overview, guest cast, crew, public average. */
    suspend fun episodeDetails(episodeId: EpisodeId): Result<EpisodeDetails>

    /**
     * Every backdrop and poster the source has for [id]. Defaults to none, so the fakes that
     * predate the Image Viewer keep compiling.
     */
    suspend fun images(id: MediaId): Result<TitleImages> = Result.success(TitleImages())

    /** The stills the source has for one episode. Defaults to none, as [images] does. */
    suspend fun episodeImages(episodeId: EpisodeId): Result<TitleImages> = Result.success(TitleImages())

    /**
     * One person and their credits. Defaulted to a failure so the hand-written fakes of this contract
     * (one per ui test suite) keep compiling; [TmdbSearchRepository][com.codingpit.muviss.feature.search.data.TmdbSearchRepository] overrides it.
     */
    suspend fun person(id: PersonId): Result<PersonDetails> = Result.failure(UnsupportedOperationException("This source has no person details"))
}
