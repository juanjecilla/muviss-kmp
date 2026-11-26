package com.codingpit.muviss.feature.search.domain

import com.codingpit.muviss.models.EpisodeDetails
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.ImageKind
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.PagedResult
import com.codingpit.muviss.models.PersonDetails
import com.codingpit.muviss.models.PersonId
import com.codingpit.muviss.models.TitleImage
import com.codingpit.muviss.models.WatchProviders
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Drops from [this] every title whose id is in [shown], and every repeat of an
 * id earlier in the list. The first occurrence wins and the order of the rest
 * is kept.
 *
 * TMDB paging drifts while a list grows: a title can come back on page 2 that
 * page 1 already showed. A paged list that appends pages blindly then carries
 * the same [MediaId] twice, and a keyed `LazyVerticalGrid` crashes on it. So
 * every paged read passes the ids already on screen as [shown] and gets back a
 * page that is unique by construction. The page can come back shorter than
 * TMDB sent it; its paging fields are left alone.
 */
internal fun List<MediaSummary>.withoutShown(shown: Set<MediaId>): List<MediaSummary> {
    val seen = HashSet(shown)
    return filter { seen.add(it.id) }
}

/**
 * Searches media by free-text query, one page at a time. Blank queries resolve to an empty page.
 * [shown] is the ids already on screen; see [withoutShown].
 */
class SearchMediaUseCase(private val repository: SearchRepository) {
    suspend operator fun invoke(query: String, page: Int = 1, shown: Set<MediaId> = emptySet()): Result<PagedResult<MediaSummary>> = if (query.isBlank()) {
        Result.success(PagedResult(emptyList(), page = 1, totalPages = 1))
    } else {
        repository.search(query.trim(), page).map { it.copy(items = it.items.withoutShown(shown)) }
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

/**
 * Popularity-sorted browse backing the Search tab's discover carousels and genre drill-down.
 * [shown] is the ids already on screen; see [withoutShown].
 */
class DiscoverMediaUseCase(private val repository: SearchRepository) {
    suspend operator fun invoke(
        type: MediaType,
        page: Int = 1,
        genreId: String? = null,
        excludedGenreIds: Set<String> = emptySet(),
        shown: Set<MediaId> = emptySet(),
    ): Result<PagedResult<MediaSummary>> = repository.discover(type, page, genreId, excludedGenreIds)
        .map { it.copy(items = it.items.withoutShown(shown)) }
}

/** Loads the localized genre list backing the discover chip rows. */
class GenresUseCase(private val repository: SearchRepository) {
    suspend operator fun invoke(type: MediaType): Result<List<Genre>> = repository.genres(type)
}

/** Loads where a title can be streamed/rented/bought, for the Detail screen's "Where to watch" section. */
class WatchProvidersUseCase(private val repository: SearchRepository) {
    suspend operator fun invoke(id: MediaId): Result<WatchProviders> = repository.watchProviders(id)
}

/**
 * Loads "More like this" recommendations for [id] (EPIC 16); the Detail
 * screen falls back to [SimilarMediaUseCase] when this is empty (see
 * [MoreLikeThisUseCase]), and `SearchViewModel` also calls this directly to
 * fetch each seed's recommendations for Discover's "For you" section.
 */
class RecommendationsUseCase(private val repository: SearchRepository) {
    suspend operator fun invoke(id: MediaId, page: Int = 1, fresh: Boolean = false): Result<PagedResult<MediaSummary>> = repository.recommendations(id, page, fresh)
}

/** Loads titles similar to [id] (EPIC 16); the Detail screen's recommendations fallback (see [MoreLikeThisUseCase]). */
class SimilarMediaUseCase(private val repository: SearchRepository) {
    suspend operator fun invoke(id: MediaId, page: Int = 1): Result<PagedResult<MediaSummary>> = repository.similar(id, page)
}

/**
 * Loads the Detail screen's "More like this" row (EPIC 16): [RecommendationsUseCase],
 * falling back to [SimilarMediaUseCase] when TMDB has no recommendations for
 * [id] (common for very new or niche titles). Composing the fallback here
 * — rather than in `DetailViewModel` — keeps that choice testable as domain
 * logic and keeps the view model's own constructor from growing by two
 * dependencies instead of one.
 */
class MoreLikeThisUseCase(
    private val recommendations: RecommendationsUseCase,
    private val similar: SimilarMediaUseCase,
) {
    suspend operator fun invoke(id: MediaId, page: Int = 1): Result<PagedResult<MediaSummary>> {
        val recommended = recommendations(id, page)
        if (recommended.getOrNull()?.items?.isNotEmpty() == true) return recommended.map { it.distinctByMediaId() }
        return similar(id, page).map { it.distinctByMediaId() }
    }

    /** The row is a `LazyColumn` keyed by [MediaId]: one title must not reach it twice (#733). */
    private fun PagedResult<MediaSummary>.distinctByMediaId(): PagedResult<MediaSummary> = copy(items = items.withoutShown(emptySet()))
}

/** Loads one episode's full detail for the episode screen. */
class EpisodeDetailUseCase(private val repository: SearchRepository) {
    suspend operator fun invoke(episodeId: EpisodeId): Result<EpisodeDetails> = repository.episodeDetails(episodeId)
}

/** A Gallery as the Image Viewer pages it. */
data class Gallery(val images: List<TitleImage>)

/**
 * The images the Image Viewer pages through, starting from the one the person tapped.
 *
 * Opens at once on [seed] — the artwork already on the screen, so already in the image cache — and
 * fills in the rest when the source answers. The seed stays the first page throughout, so the page
 * the person is looking at does not move when the list arrives. A source that is offline, or has
 * nothing, leaves the Gallery as the seed alone; there is no error to show for a request the person
 * never made.
 *
 * With [episodeId] the Gallery is that episode's stills; otherwise it is [kind] of [mediaId]'s art.
 */
class LoadGalleryUseCase(private val repository: SearchRepository) {
    operator fun invoke(mediaId: MediaId, episodeId: EpisodeId?, kind: ImageKind, seed: TitleImage): Flow<Gallery> = flow {
        emit(Gallery(listOf(seed)))
        val fetched = if (episodeId != null) repository.episodeImages(episodeId) else repository.images(mediaId)
        val others = fetched.getOrNull()?.of(kind).orEmpty()
        if (others.isNotEmpty()) emit(merge(seed, others))
    }

    /** The seed first — at the source's larger size when it is in [others] — then the rest, once each. */
    internal fun merge(seed: TitleImage, others: List<TitleImage>): Gallery {
        val sameFile = others.firstOrNull { it.fileKey == seed.fileKey }
        val rest = others.filter { it.fileKey != seed.fileKey }.distinctBy { it.fileKey }
        return Gallery(listOf(sameFile ?: seed) + rest)
    }
}

/** One person's page: who they are and what they are credited on. */
class PersonDetailUseCase(private val repository: SearchRepository) {
    suspend operator fun invoke(id: PersonId): Result<PersonDetails> = repository.person(id)
}
