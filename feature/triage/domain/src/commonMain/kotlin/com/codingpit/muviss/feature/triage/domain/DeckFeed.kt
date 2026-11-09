package com.codingpit.muviss.feature.triage.domain

import com.codingpit.muviss.core.common.flags.SnoozePlacement
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import kotlinx.coroutines.flow.first

/**
 * Where a deck's cards come from and where its position is kept. The catalogue deck ([LoadDeckUseCase]) and a deck
 * seeded from Next Pick (#554, [SeededDeckFeed]) both drive the same screen through this, so the screen does not
 * know which it is showing beyond [seeded].
 */
interface DeckFeed {
    /** True for a deck handed titles by Next Pick. Such a deck reads no genres, applies no mutes and saves no position. */
    val seeded: Boolean get() = false

    /** Reads what a refill must not offer, once, and returns the session its batches are read from. */
    suspend fun open(placement: SnoozePlacement): DeckSession

    /** Where the deck stood for [filter] when it was last saved, or page 1. */
    suspend fun restoreCursor(filter: DeckFilter): DeckCursor

    /** Saves where the deck stands. [unactedMovie] and [unactedTv] are the earliest pages still holding an unacted card. */
    suspend fun checkpoint(filter: DeckFilter, cursor: DeckCursor, unactedMovie: Int?, unactedTv: Int?)
}

/** The titles a seeded deck offers, read from wherever Next Pick put them. */
fun interface DeckSeeds {
    fun current(): List<MediaSummary>
}

/**
 * A deck over the titles Next Pick handed over (#554). It is a list, not a catalogue, so it differs from
 * [LoadDeckUseCase] in the ways #555 names:
 * - **Rank order.** The seeds come out in the order given. [DeckLoader] would alternate movies and TV by page.
 * - **No mutes.** The engine already applied the person's muting, including a genre lifted for a mood (`effectiveMuting`),
 *   so the client does not hide anything the engine chose.
 * - **No Snoozes.** A seeded deck does not replay due Snoozes. Their Snooze is still a row, and returns through the
 *   catalogue deck.
 * - **No saved position.** [checkpoint] writes nothing, so a seeded deck never overwrites the position of the
 *   catalogue deck (#722), and [restoreCursor] is always page 1.
 *
 * The cursor a batch returns is exhausted for both types: the seeds are one batch, and the rest of them come back as
 * [DeckBatch.overflow]. The seeds are snapshotted when the feed is built, so a later handoff does not change a deck
 * that is on screen.
 *
 * The exclusions are read once per refill, like the catalogue's. A title ruled on, saved or snoozed since the
 * handoff is not offered again. Undo removes a collection entry that the deck wrote, so it is only reachable for a
 * seed that was out of the library when the deck opened. A title the person adds from Detail while the deck is open
 * can still be undone out of the library, as it can in the catalogue deck; that is recorded in the PR for #555.
 */
class SeededDeckFeed(
    seeds: DeckSeeds,
    private val repository: TriageDecisionRepository,
    private val snoozes: TriageSnoozeRepository,
    private val collectionApi: CollectionApi,
) : DeckFeed {

    private val offered: List<MediaSummary> = seeds.current()

    override val seeded: Boolean get() = true

    override suspend fun open(placement: SnoozePlacement): DeckSession {
        // Every pending Snooze is excluded, due or not: a snoozed seed must not reappear on a reset.
        val settled = repository.observeDecidedIds().first() +
            collectionApi.observeSummaries().first().map { it.mediaId } +
            snoozes.observeSnoozedIds().first()
        val pending = offered.filter { it.id !in settled }
        return DeckSession { filter, _, alreadyShown, wanted ->
            val fresh = pending.filter { (filter.type == null || it.type == filter.type) && it.id !in alreadyShown }
            Result.success(DeckBatch(cards = fresh.take(wanted), cursor = EXHAUSTED, overflow = fresh.drop(wanted)))
        }
    }

    override suspend fun restoreCursor(filter: DeckFilter): DeckCursor = DeckCursor()

    override suspend fun checkpoint(filter: DeckFilter, cursor: DeckCursor, unactedMovie: Int?, unactedTv: Int?) = Unit

    private companion object {
        /** Both catalogues read to the end: the seeds are the whole deck, so a refill never asks for more. */
        val EXHAUSTED = DeckCursor(moviesExhausted = true, tvExhausted = true)
    }
}
