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
import kotlin.test.assertNotNull
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

        // Exactly the aired episodes are ticked (EpisodeOrdering.airedBy), the unaired one is not.
        assertEquals(
            com.codingpit.muviss.feature.progress.api.EpisodeOrdering.airedBy(ongoingDetails.seasons, TODAY_EPOCH_DAY).toSet(),
            h.progress.seen.value,
        )
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

        assertEquals(setOf(EpisodeId.forMovie(film.id)), h.progress.seen.value)
        assertEquals(WatchStatus.WATCHED, deriveFor(movieDetails(film), seen = 1))
    }

    @Test
    fun caught_up_never_ticks_an_aired_special_and_records_no_play_for_one() = runTest {
        // ADR 0038: triage ticks regular seasons only, so a special stays unseen and its viewing is never recorded.
        val withSpecial = show("1401", "Doctor Who")
        val details = tvDetails(
            summary = withSpecial,
            seasons = listOf(
                0 to listOf(TODAY_EPOCH_DAY - 40),
                1 to listOf(TODAY_EPOCH_DAY - 30, TODAY_EPOCH_DAY - 23, TODAY_EPOCH_DAY + 4),
            ),
        )
        val h = Harness(details = FakeTriageDetailsSource(byId = mapOf(withSpecial.id to details)))

        h.useCase(withSpecial, TriageVerdict.CAUGHT_UP).getOrThrow()

        assertEquals(
            setOf(EpisodeId(withSpecial.id, 1, 1), EpisodeId(withSpecial.id, 1, 2)),
            h.progress.seen.value,
        )
        assertEquals(
            listOf(
                com.codingpit.muviss.feature.progress.api.EpisodePlay.idOf(EpisodeId(withSpecial.id, 1, 1), NOW_EPOCH_MS),
                com.codingpit.muviss.feature.progress.api.EpisodePlay.idOf(EpisodeId(withSpecial.id, 1, 2), NOW_EPOCH_MS),
            ),
            h.repository.decisions.value.getValue(withSpecial.id).tickedPlayIds,
        )
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

    // --- a failed claim write after the add (#1071) ---

    @Test
    fun a_failed_claim_write_after_the_add_takes_the_added_entry_back() = runTest {
        val h = harness()
        h.repository.failClaimWrite = IllegalStateException("disk full")

        assertTrue(h.useCase(endedShow, TriageVerdict.CAUGHT_UP).isFailure)

        assertEquals(listOf(endedShow.id), h.collection.removed, "the add this attempt made is rolled back")
        assertEquals(emptyList(), h.collection.summaries.value, "the library is as it was before the verdict")
        assertFalse(h.repository.decisions.value.getValue(endedShow.id).resolved)
        assertFalse(h.repository.decisions.value.getValue(endedShow.id).createdEntry, "no claim was written")
    }

    @Test
    fun a_retry_after_a_rolled_back_claim_write_claims_and_undo_removes_the_entry() = runTest {
        val h = harness()
        h.repository.failClaimWrite = IllegalStateException("disk full")
        h.useCase(endedShow, TriageVerdict.CAUGHT_UP)

        h.useCase.retry(h.repository.decisions.value.getValue(endedShow.id)).getOrThrow()
        val claim = h.repository.decisions.value.getValue(endedShow.id)
        assertTrue(claim.createdEntry)
        assertNotNull(claim.entryAddedAtEpochMs)

        UndoDecisionUseCase(h.repository, h.collection, h.progress)(endedShow.id, TriageVerdict.CAUGHT_UP)

        assertEquals(emptyList(), h.collection.summaries.value, "undo takes the retried claim's entry back")
    }

    @Test
    fun a_cancelled_claim_write_still_takes_the_added_entry_back() = runTest {
        val h = harness()
        h.repository.failClaimWrite = kotlinx.coroutines.CancellationException("left the screen")

        assertTrue(h.useCase(endedShow, TriageVerdict.CAUGHT_UP).isFailure)

        assertEquals(emptyList(), h.collection.summaries.value)
        assertFalse(h.repository.decisions.value.getValue(endedShow.id).resolved)
    }

    @Test
    fun a_failed_claim_write_leaves_an_entry_the_person_already_had_alone() = runTest {
        val h = harness()
        h.collection.add(endedDetails)
        h.repository.failUnclaimedClaimWrite = IllegalStateException("disk full")

        assertTrue(h.useCase(endedShow, TriageVerdict.CAUGHT_UP).isFailure, "the claim write failed, so the verdict fails")

        assertEquals(emptyList(), h.collection.removed, "an entry present before the verdict is never rolled back")
        assertEquals(listOf(endedShow.id), h.collection.summaries.value.map { it.mediaId })
    }

    // --- undo ---

    @Test
    fun undo_reverses_the_collection_save_and_the_ticks() = runTest {
        val h = harness()
        h.useCase(endedShow, TriageVerdict.CAUGHT_UP).getOrThrow()

        UndoDecisionUseCase(h.repository, h.collection, h.progress)(endedShow.id, TriageVerdict.CAUGHT_UP)

        assertEquals(listOf(endedShow.id), h.collection.removed)
        assertEquals(emptySet(), h.progress.seen.value, "the verdict's ticks are taken back")
        assertEquals(emptyList(), h.progress.cleared, "undo takes back viewings, never the whole title")
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

    // --- the claim on the library entry (#767) ---

    @Test
    fun an_entry_added_while_the_deck_was_open_survives_undo() = runTest {
        val h = harness()
        // Added from Detail after the card was shown, so the deck's exclusion read is stale.
        h.collection.add(ongoingDetails)

        h.useCase(ongoingShow, TriageVerdict.WATCHING).getOrThrow()
        UndoDecisionUseCase(h.repository, h.collection, h.progress)(ongoingShow.id, TriageVerdict.WATCHING)

        assertEquals(emptyList(), h.collection.removed)
        assertEquals(emptyList(), h.progress.cleared)
        assertTrue(h.collection.summaries.value.any { it.mediaId == ongoingShow.id })
    }

    @Test
    fun an_entry_added_while_the_deck_was_open_is_not_claimed_by_the_verdict() = runTest {
        val h = harness()
        h.collection.add(ongoingDetails)

        h.useCase(ongoingShow, TriageVerdict.LATER).getOrThrow()

        assertFalse(h.repository.decisions.value.getValue(ongoingShow.id).createdEntry)
    }

    @Test
    fun an_entry_the_verdict_created_is_claimed_and_removed_by_undo() = runTest {
        val h = harness()

        h.useCase(endedShow, TriageVerdict.CAUGHT_UP).getOrThrow()
        assertTrue(h.repository.decisions.value.getValue(endedShow.id).createdEntry)

        UndoDecisionUseCase(h.repository, h.collection, h.progress)(endedShow.id, TriageVerdict.CAUGHT_UP)

        assertEquals(listOf(endedShow.id), h.collection.removed)
        assertEquals(emptyList(), h.progress.cleared, "undo takes back viewings, never the whole title")
    }

    @Test
    fun a_skip_never_claims_an_entry() = runTest {
        val h = harness()

        h.useCase(film, TriageVerdict.SKIP).getOrThrow()

        assertFalse(h.repository.decisions.value.getValue(film.id).createdEntry)
    }

    @Test
    fun a_retry_retires_a_pre_stamp_claim_when_an_entry_is_already_there() = runTest {
        val h = harness()
        // A claim recorded before the stamp column (#1058): it names no entry, so a present entry cannot be proven to be
        // the one it made. The retry retires it, and undo leaves the entry alone.
        h.repository.record(
            TriageDecision.of(endedShow, TriageVerdict.CAUGHT_UP, NOW_EPOCH_MS, resolved = false, createdEntry = true),
        )
        h.collection.add(endedDetails)

        h.useCase.retry(h.repository.decisions.value.getValue(endedShow.id)).getOrThrow()
        assertFalse(h.repository.decisions.value.getValue(endedShow.id).createdEntry, "the retry retires a pre-stamp claim")
        UndoDecisionUseCase(h.repository, h.collection, h.progress)(endedShow.id, TriageVerdict.CAUGHT_UP)

        assertEquals(emptyList(), h.collection.removed)
    }

    @Test
    fun a_pre_stamp_claim_retried_with_no_entry_present_claims_the_entry_its_add_makes() = runTest {
        val h = harness()
        // A row written before the stamp column, and the entry it named is gone: the retry's add makes it now.
        h.repository.record(
            TriageDecision.of(endedShow, TriageVerdict.CAUGHT_UP, NOW_EPOCH_MS, resolved = false, createdEntry = true),
        )

        h.useCase.retry(h.repository.decisions.value.getValue(endedShow.id)).getOrThrow()

        val retried = h.repository.decisions.value.getValue(endedShow.id)
        assertTrue(retried.createdEntry, "an add that makes the entry keeps the claim")
        assertNotNull(retried.entryAddedAtEpochMs)
    }

    @Test
    fun a_retry_keeps_a_stamped_claim_the_first_attempt_recorded() = runTest {
        val h = harness()
        // The first attempt's add made the entry and stamped it. The retry finds that entry still there, so the
        // claim, and the stamp it carries, stay as they were.
        h.useCase(endedShow, TriageVerdict.CAUGHT_UP).getOrThrow()
        val first = h.repository.decisions.value.getValue(endedShow.id)
        assertNotNull(first.entryAddedAtEpochMs)
        h.useCase.retry(first).getOrThrow()

        val retried = h.repository.decisions.value.getValue(endedShow.id)
        assertTrue(retried.createdEntry, "the retry keeps a stamped claim")
        assertEquals(first.entryAddedAtEpochMs, retried.entryAddedAtEpochMs)
    }

    @Test
    fun a_failed_undo_leaves_the_claim_readable_so_a_retry_finishes_the_cleanup() = runTest {
        val h = harness()
        h.useCase(endedShow, TriageVerdict.CAUGHT_UP).getOrThrow()
        val undo = UndoDecisionUseCase(h.repository, h.collection, h.progress)
        h.collection.failRemove = IllegalStateException("disk full")

        assertFailsWith<IllegalStateException> { undo(endedShow.id, TriageVerdict.CAUGHT_UP) }

        // The cleanup failed before the decision was tombstoned, so the claim is still readable.
        assertTrue(h.repository.decisions.value.getValue(endedShow.id).createdEntry)
        assertEquals(emptyList(), h.repository.restored)

        h.collection.failRemove = null
        undo(endedShow.id, TriageVerdict.CAUGHT_UP)

        assertEquals(listOf(endedShow.id), h.collection.removed)
        assertEquals(listOf(endedShow.id), h.repository.restored)
    }

    @Test
    fun a_failed_fetch_takes_no_claim_so_a_title_added_before_the_retry_survives_undo() = runTest {
        val failing = harness(failure = IllegalStateException("offline"))
        failing.useCase(endedShow, TriageVerdict.CAUGHT_UP)
        assertFalse(failing.repository.decisions.value.getValue(endedShow.id).createdEntry)
        // The person adds the title from Detail while the decision is unresolved.
        failing.collection.add(endedDetails)

        val retrying = RecordDecisionUseCase(
            failing.repository,
            failing.collection,
            failing.progress,
            FakeTriageDetailsSource(byId = mapOf(endedShow.id to endedDetails)),
            FakeClock(NOW_EPOCH_MS),
        )
        retrying.retry(failing.repository.decisions.value.getValue(endedShow.id)).getOrThrow()
        UndoDecisionUseCase(failing.repository, failing.collection, failing.progress)(endedShow.id, TriageVerdict.CAUGHT_UP)

        assertEquals(emptyList(), failing.collection.removed)
    }

    @Test
    fun undo_with_no_decision_row_removes_nothing_from_the_library() = runTest {
        val h = harness()
        h.collection.add(endedDetails)

        UndoDecisionUseCase(h.repository, h.collection, h.progress)(endedShow.id, TriageVerdict.CAUGHT_UP)

        assertEquals(emptyList(), h.collection.removed)
        assertEquals(emptyList(), h.progress.cleared)
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
