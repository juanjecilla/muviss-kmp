package com.codingpit.muviss.feature.triage.domain

import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.PagedResult

/** Which slice of the catalogue the deck is drawing from. */
data class DeckFilter(
    /** null = both, interleaved. */
    val type: MediaType? = null,
    val genreId: String? = null,
)

/** How far into each catalogue the deck has read. Opaque to the UI; just pass it back. */
data class DeckCursor(
    val moviePage: Int = 1,
    val tvPage: Int = 1,
    val moviesExhausted: Boolean = false,
    val tvExhausted: Boolean = false,
) {
    fun exhaustedFor(filter: DeckFilter): Boolean = when (filter.type) {
        MediaType.MOVIE -> moviesExhausted
        MediaType.TV -> tvExhausted
        null -> moviesExhausted && tvExhausted
    }
}

/** A batch of cards plus where to resume. */
data class DeckBatch(
    val cards: List<MediaSummary>,
    val cursor: DeckCursor,
) {
    val exhausted: Boolean get() = cards.isEmpty()
}

/**
 * The seam onto whatever supplies candidate titles. One implementation today
 * (`TmdbDeckSource`, TMDB's `/discover` ordered by popularity — dense with
 * titles a user certainly has an opinion about, which is what makes the
 * backfill phase work). Kept an interface so a taste-blended feed can be
 * swapped in without [DeckLoader] or the UI changing.
 */
interface DeckSource {
    suspend fun page(type: MediaType, page: Int, genreId: String?): Result<PagedResult<MediaSummary>>

    /** Genres to narrow the deck by. Failure is not fatal — the deck just offers no genre chips. */
    suspend fun genres(type: MediaType): Result<List<Genre>>
}

/**
 * Turns pages of catalogue into a deck: interleaves movies and TV, drops
 * anything the user has already ruled on or already has in their collection,
 * and keeps reading until it has enough cards to hand over.
 *
 * The refill loop is bounded by [MAX_PAGES_PER_BATCH]. Without it, a user deep
 * into their backfill — where nearly every candidate on every page is already
 * decided — would silently walk the entire catalogue in one call. Returning a
 * short batch (or none) is the honest answer; the caller shows an empty deck.
 */
class DeckLoader(private val source: DeckSource) {

    suspend fun load(
        filter: DeckFilter,
        cursor: DeckCursor,
        excluded: Set<MediaId>,
        wanted: Int = DEFAULT_BATCH_SIZE,
    ): Result<DeckBatch> {
        val collected = mutableListOf<MediaSummary>()
        val seen = excluded.toMutableSet()
        var current = cursor

        repeat(MAX_PAGES_PER_BATCH) {
            if (collected.size >= wanted || current.exhaustedFor(filter)) return@repeat
            val type = current.nextType(filter) ?: return@repeat
            val pageNumber = current.pageFor(type)

            val page = source.page(type, pageNumber, filter.genreId)
                .getOrElse { return Result.failure(it) }

            page.items
                .filter { seen.add(it.id) }
                .forEach { collected += it }

            current = current.advance(type, lastPage = page.page >= page.totalPages)
        }

        return Result.success(DeckBatch(collected.take(wanted), current))
    }

    /**
     * Alternates movie/TV so a mixed deck never shows ten films in a row.
     * Falls back to whichever side still has pages once the other runs dry.
     */
    private fun DeckCursor.nextType(filter: DeckFilter): MediaType? = when (filter.type) {
        MediaType.MOVIE -> MediaType.MOVIE.takeUnless { moviesExhausted }

        MediaType.TV -> MediaType.TV.takeUnless { tvExhausted }

        null -> when {
            moviesExhausted && tvExhausted -> null
            moviesExhausted -> MediaType.TV
            tvExhausted -> MediaType.MOVIE
            moviePage <= tvPage -> MediaType.MOVIE
            else -> MediaType.TV
        }
    }

    private fun DeckCursor.pageFor(type: MediaType): Int = if (type == MediaType.MOVIE) moviePage else tvPage

    private fun DeckCursor.advance(type: MediaType, lastPage: Boolean): DeckCursor = when (type) {
        MediaType.MOVIE -> copy(moviePage = moviePage + 1, moviesExhausted = lastPage)
        MediaType.TV -> copy(tvPage = tvPage + 1, tvExhausted = lastPage)
    }

    companion object {
        /** Enough to swipe through without a visible refill, small enough to load fast. */
        const val DEFAULT_BATCH_SIZE = 10

        /** Bounds the refill loop when almost everything on a page is already decided. */
        const val MAX_PAGES_PER_BATCH = 5
    }
}
