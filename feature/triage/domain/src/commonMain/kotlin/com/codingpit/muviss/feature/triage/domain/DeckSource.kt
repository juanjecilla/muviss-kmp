package com.codingpit.muviss.feature.triage.domain

import com.codingpit.muviss.core.common.flags.SnoozePlacement
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

/**
 * The Snoozes that have come due, and where they should re-enter the deck
 * (EPIC 42, ADR 0023). One value rather than two parameters: the cards and the
 * policy for placing them are only ever meaningful together.
 */
data class DueSnoozes(
    val cards: List<MediaSummary> = emptyList(),
    val placement: SnoozePlacement = SnoozePlacement.DEFAULT,
)

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
 * decided — would silently walk the entire catalogue in one call. A short
 * batch (or none) is returned with the advanced cursor; an empty batch does
 * not mean the catalogue is spent — [DeckCursor.exhaustedFor] does. The deck
 * view model calls again until it has cards or the cursor is exhausted.
 *
 * Since EPIC 42 it also merges in Snoozes that have come due. They are a
 * second source, not a filter: the catalogue is TMDB `/discover` by
 * popularity, so a title snoozed three months ago will not be on page 1 when
 * it comes back and there is no way to reach it except by holding onto it.
 */
class DeckLoader(private val source: DeckSource) {

    suspend fun load(
        filter: DeckFilter,
        cursor: DeckCursor,
        excluded: Set<MediaId>,
        dueSnoozes: DueSnoozes = DueSnoozes(),
        wanted: Int = DEFAULT_BATCH_SIZE,
    ): Result<DeckBatch> {
        val collected = mutableListOf<MediaSummary>()
        val seen = excluded.toMutableSet()
        var current = cursor
        val placement = dueSnoozes.placement

        // Due Snoozes obey the filter like anything else: narrowing the deck to
        // comedies should not smuggle a snoozed horror film back in.
        val returning = dueSnoozes.cards
            .filter { it.matches(filter) && seen.add(it.id) }
            .let { if (placement == SnoozePlacement.MIXED_IN) it.take(MAX_SNOOZES_PER_BATCH) else it.take(wanted) }

        // FIRST fills the batch before the catalogue is asked for anything, so
        // a short batch of returning titles is a complete answer.
        if (placement == SnoozePlacement.FIRST) collected += returning

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

        val merged = when (placement) {
            // Already at the front.
            SnoozePlacement.FIRST -> collected

            SnoozePlacement.LAST -> collected.take(wanted - returning.size).plus(returning)

            // Trimmed to leave room *before* mixing, so the truncation below
            // can never be what drops a returning title.
            SnoozePlacement.MIXED_IN -> collected.take(wanted - returning.size).mixIn(returning)
        }

        return Result.success(DeckBatch(merged.take(wanted), current))
    }

    /**
     * Spreads [returning] through [this] at even intervals rather than
     * clustering them, so a long absence drains a couple of cards at a time
     * instead of burying discovery under everything that came due at once —
     * the failure ADR 0010 calls "a tidied-up library resurfacing card by
     * card". With an empty catalogue side this degrades to the returning
     * titles alone, which is correct: they are still the honest answer.
     *
     * Slots are computed over the merged length so every returning title lands
     * *inside* the batch. Appending the remainder instead put the last one past
     * `wanted`, where the caller's `take` silently dropped it.
     */
    private fun List<MediaSummary>.mixIn(returning: List<MediaSummary>): List<MediaSummary> {
        if (returning.isEmpty()) return this
        if (isEmpty()) return returning

        val total = size + returning.size
        // Never slot 0: the first card of a refill should be a new one, or
        // opening the deck looks like nothing has changed.
        val slots = returning.indices.map { i -> maxOf(1, ((i + 1) * total) / (returning.size + 1)) }.toSet()
        val fresh = ArrayDeque(this)
        val queue = ArrayDeque(returning)
        return (0 until total).map { index ->
            val takeReturning = index in slots && queue.isNotEmpty()
            if (takeReturning || fresh.isEmpty()) queue.removeFirst() else fresh.removeFirst()
        }
    }

    private fun MediaSummary.matches(filter: DeckFilter): Boolean = filter.type == null || filter.type == type

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

        /**
         * How many due Snoozes one MIXED_IN batch may carry. Two of ten: enough
         * that a backlog visibly drains, few enough that the deck still feels
         * like discovery rather than a replay of everything postponed.
         */
        const val MAX_SNOOZES_PER_BATCH = 2
    }
}
