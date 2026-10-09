package com.codingpit.muviss.feature.triage.domain

import com.codingpit.muviss.core.model.WatchProgress
import com.codingpit.muviss.core.model.WatchStatusCalculator
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.feature.triage.api.TriageVerdict
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.ProductionStatus
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordDecisionUseCaseTest {

    private val ongoingShow = show("1399", "Game of Thrones")
    private val ongoingDetails = tvDetails(
        summary = ongoingShow,
        seasons = listOf(1 to listOf(TODAY_EPOCH_DAY - 30, TODAY_EPOCH_DAY - 23, TODAY_EPOCH_DAY + 4)),
        productionStatus = ProductionStatus.RETURNING,
    )
    private val endedShow = show("1400", "Breaking Bad")
    private val endedDetails = tvDetails(
        summary = endedShow,
        seasons = listOf(1 to listOf(TODAY_EPOCH_DAY - 90, TODAY_EPOCH_DAY - 83)),
        productionStatus = ProductionStatus.ENDED,
    )
    private val film = movie("603", "The Matrix")

    private class Harness(
        val repository: FakeTriageDecisionRepository = FakeTriageDecisionRepository(),
        val collection: FakeCollectionApi = FakeCollectionApi(),
        val progress: FakeProgressApi = FakeProgressApi(),
        val details: FakeTriageDetailsSource,
    ) {
        val useCase = RecordDecisionUseCase(repository, collection, progress, details, FakeClock(NOW_EPOCH_MS))
    }

    private fun harness(failure: Throwable? = null) = Harness(
        details = FakeTriageDetailsSource(
            byId = mapOf(
                ongoingShow.id to ongoingDetails,
                endedShow.id to endedDetails,
                film.id to movieDetails(film),
            ),
            failure = failure,
        ),
    )

    // --- SKIP ---

    @Test
    fun skip_records_a_decision_and_saves_nothing() = runTest {
        val h = harness()

        h.useCase(ongoingShow, TriageVerdict.SKIP).getOrThrow()

        assertEquals(TriageVerdict.SKIP, h.repository.decisions.value[ongoingShow.id]?.verdict)
        assertTrue(h.repository.decisions.value.getValue(ongoingShow.id).resolved)
        assertEquals(emptyList(), h.collection.added)
        // A skip must never cost a network round trip — it decides nothing that needs episodes.
        assertEquals(0, h.details.fetchCalls)
    }

    @Test
    fun skip_works_the_same_for_a_movie() = runTest {
        val h = harness()

        h.useCase(film, TriageVerdict.SKIP).getOrThrow()

        assertEquals(TriageVerdict.SKIP, h.repository.decisions.value[film.id]?.verdict)
        assertEquals(emptyList(), h.collection.added)
    }

    // --- LATER ---

    @Test
    fun later_saves_the_show_without_ticking_anything() = runTest {
        val h = harness()

        h.useCase(ongoingShow, TriageVerdict.LATER).getOrThrow()

        assertEquals(listOf(ongoingShow.id), h.collection.added.map { it.id })
        assertEquals(emptyList(), h.progress.ticked)
        assertEquals(WatchStatus.NOT_STARTED, deriveFor(ongoingDetails, seen = 0))
    }

    @Test
    fun later_saves_a_movie_without_marking_it_watched() = runTest {
        val h = harness()

        h.useCase(film, TriageVerdict.LATER).getOrThrow()

        assertEquals(listOf(film.id), h.collection.added.map { it.id })
        assertEquals(emptyList(), h.progress.moviesWatched)
    }

    // --- WATCHING ---

    @Test
    fun watching_ticks_the_first_aired_episode_so_the_show_derives_watching() = runTest {
        val h = harness()

        h.useCase(ongoingShow, TriageVerdict.WATCHING).getOrThrow()

        assertEquals(listOf(EpisodeId(ongoingShow.id, 1, 1)), h.progress.ticked)
        assertEquals(WatchStatus.WATCHING, deriveFor(ongoingDetails, seen = 1))
    }

    @Test
    fun watching_a_show_with_nothing_aired_yet_saves_it_without_a_tick() = runTest {
        val unaired = show("2000", "Not out yet")
        val h = Harness(
            details = FakeTriageDetailsSource(
                mapOf(unaired.id to tvDetails(unaired, seasons = listOf(1 to listOf(TODAY_EPOCH_DAY + 30)))),
            ),
        )

        h.useCase(unaired, TriageVerdict.WATCHING).getOrThrow()

        assertEquals(listOf(unaired.id), h.collection.added.map { it.id })
        assertEquals(emptyList(), h.progress.ticked)
    }

    @Test
    fun watching_is_rejected_for_a_movie() = runTest {
        val h = harness()

        // A movie is never partway through — the UI never offers this, and the
        // use case refuses it outright rather than writing a nonsense tick.
        assertFailsWith<IllegalArgumentException> { h.useCase(film, TriageVerdict.WATCHING) }
        assertEquals(emptyMap(), h.repository.decisions.value)
    }

    // --- CAUGHT_UP ---

    @Test
    fun caught_up_on_an_ongoing_show_derives_watched_not_finished() = runTest {
        val h = harness()

        h.useCase(ongoingShow, TriageVerdict.CAUGHT_UP).getOrThrow()

        assertEquals(1, h.progress.markedAllAired.size)
        assertEquals(TODAY_EPOCH_DAY, h.progress.markedAllAired.single().second)
        // Two of three episodes have aired: all aired seen, production ongoing.
        assertEquals(WatchStatus.WATCHED, deriveFor(ongoingDetails, seen = 2))
    }

    @Test
    fun caught_up_on_an_ended_show_derives_finished() = runTest {
        val h = harness()

        h.useCase(endedShow, TriageVerdict.CAUGHT_UP).getOrThrow()

        assertEquals(WatchStatus.FINISHED, deriveFor(endedDetails, seen = 2))
    }

    @Test
    fun caught_up_on_a_movie_uses_the_movie_tick() = runTest {
        val h = harness()

        h.useCase(film, TriageVerdict.CAUGHT_UP).getOrThrow()

        assertEquals(listOf(film.id), h.progress.moviesWatched)
        assertEquals(emptyList(), h.progress.markedAllAired)
        assertEquals(WatchStatus.WATCHED, deriveFor(movieDetails(film), seen = 1))
    }

    // --- failure handling ---

    @Test
    fun a_failed_fetch_still_leaves_the_decision_recorded_but_unresolved() = runTest {
        val h = harness(failure = IllegalStateException("offline"))

        val result = h.useCase(ongoingShow, TriageVerdict.CAUGHT_UP)

        assertTrue(result.isFailure)
        // The whole point: dedupe survives a failed commit, so the deck does
        // not re-ask about a title the user already ruled on.
        val recorded = h.repository.decisions.value.getValue(ongoingShow.id)
        assertEquals(TriageVerdict.CAUGHT_UP, recorded.verdict)
        assertFalse(recorded.resolved)
        assertEquals(emptyList(), h.collection.added)
    }

    @Test
    fun retry_completes_a_previously_failed_commit() = runTest {
        val h = harness()
        h.repository.record(
            TriageDecision.of(endedShow, TriageVerdict.CAUGHT_UP, nowEpochMs = NOW_EPOCH_MS, resolved = false),
        )

        h.useCase.retry(h.repository.decisions.value.getValue(endedShow.id)).getOrThrow()

        assertEquals(listOf(endedShow.id), h.collection.added.map { it.id })
        assertTrue(h.repository.decisions.value.getValue(endedShow.id).resolved)
    }

    @Test
    fun retry_unresolved_sweeps_every_incomplete_decision() = runTest {
        val h = harness()
        h.repository.record(TriageDecision.of(endedShow, TriageVerdict.LATER, NOW_EPOCH_MS, resolved = false))
        h.repository.record(TriageDecision.of(film, TriageVerdict.LATER, NOW_EPOCH_MS, resolved = false))
        h.repository.record(TriageDecision.of(ongoingShow, TriageVerdict.SKIP, NOW_EPOCH_MS, resolved = true))

        val completed = RetryUnresolvedUseCase(h.repository, h.useCase)()

        assertEquals(2, completed)
        assertTrue(h.repository.decisions.value.values.all { it.resolved })
    }

    // --- undo ---

    @Test
    fun undo_reverses_the_collection_save_and_the_ticks() = runTest {
        val h = harness()
        h.useCase(endedShow, TriageVerdict.CAUGHT_UP).getOrThrow()

        UndoDecisionUseCase(h.repository, h.collection, h.progress)(endedShow.id, TriageVerdict.CAUGHT_UP)

        assertEquals(listOf(endedShow.id), h.collection.removed)
        assertEquals(listOf(endedShow.id), h.progress.cleared)
        assertEquals(emptyMap(), h.repository.decisions.value)
    }

    @Test
    fun undoing_a_skip_touches_nothing_but_the_decision() = runTest {
        val h = harness()
        h.useCase(endedShow, TriageVerdict.SKIP).getOrThrow()

        UndoDecisionUseCase(h.repository, h.collection, h.progress)(endedShow.id, TriageVerdict.SKIP)

        assertEquals(emptyList(), h.collection.removed)
        assertEquals(emptyList(), h.progress.cleared)
        assertEquals(listOf(endedShow.id), h.repository.restored)
    }

    /**
     * Derives the status the collection would show, from the ticks the verdict
     * wrote — the assertion that actually matters, since triage never stores a
     * status of its own (ADR 0005).
     */
    private fun deriveFor(details: com.codingpit.muviss.models.MediaDetails, seen: Int): WatchStatus {
        val aired = if (details.type == MediaType.MOVIE) 1 else details.airedEpisodesInOrder(TODAY_EPOCH_DAY).size
        val total = if (details.type == MediaType.MOVIE) 1 else details.seasons.sumOf { it.episodes.size }
        return WatchStatusCalculator.derive(
            WatchProgress(
                mediaType = details.type,
                seenEpisodes = seen,
                airedEpisodes = aired,
                totalEpisodes = total,
                productionStatus = details.productionStatus,
            ),
        )
    }
}
