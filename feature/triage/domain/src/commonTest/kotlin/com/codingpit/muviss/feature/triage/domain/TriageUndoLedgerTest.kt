package com.codingpit.muviss.feature.triage.domain

import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.feature.triage.api.TriageVerdict
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.ProductionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What a verdict took into the library, and what undo gives back (#902, #790, #791, #792).
 *
 * Each undo case runs against a stateful fake, so an assertion reads the state a person would see: the
 * entry still in the library, the episodes still ticked.
 */
class TriageUndoLedgerTest {

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
        details: TriageDetailsSource,
    ) {
        val record = RecordDecisionUseCase(repository, collection, progress, details, FakeClock(NOW_EPOCH_MS))
        val undo = UndoDecisionUseCase(repository, collection, progress)
    }

    private fun harness(vararg known: MediaDetails) = Harness(
        details = FakeTriageDetailsSource(byId = known.associateBy { it.id }),
    )

    /** Holds a fetch until the test releases it, so the test can act while a verdict is in flight. */
    private class GatedDetailsSource(
        private val details: MediaDetails,
        private val gate: CompletableDeferred<Unit>,
    ) : TriageDetailsSource {
        override suspend fun fetch(mediaId: MediaId): Result<MediaDetails> {
            gate.await()
            return Result.success(details)
        }
    }

    private fun Harness.entryPresent(mediaId: MediaId) = collection.summaries.value.any { it.mediaId == mediaId }

    // --- #902: undo removes only the entry the verdict made, and only while it is still that entry ---

    @Test
    fun undo_keeps_an_entry_the_person_removed_and_added_back_before_the_undo() = runTest {
        val h = harness(endedDetails)
        h.record(endedShow, TriageVerdict.CAUGHT_UP).getOrThrow()
        // The person removes the title from Detail and adds it again while the undo snackbar is still pending.
        h.collection.remove(endedShow.id)
        h.collection.add(endedDetails)

        h.undo(endedShow.id, TriageVerdict.CAUGHT_UP)

        assertTrue(h.entryPresent(endedShow.id), "the entry the person added back must survive undo")
        // The only removal on record is the person's own, from Detail; undo asked for none.
        assertEquals(listOf(endedShow.id), h.collection.removed)
        assertEquals(emptyList(), h.progress.cleared)
    }

    @Test
    fun undo_still_removes_the_entry_the_verdict_made_when_nothing_else_touched_it() = runTest {
        val h = harness(endedDetails)
        h.record(endedShow, TriageVerdict.CAUGHT_UP).getOrThrow()

        h.undo(endedShow.id, TriageVerdict.CAUGHT_UP)

        assertFalse(h.entryPresent(endedShow.id))
        assertEquals(listOf(endedShow.id), h.collection.removed)
    }

    @Test
    fun a_verdict_that_creates_an_entry_stores_that_entrys_added_at_stamp() = runTest {
        val h = harness(endedDetails)

        h.record(endedShow, TriageVerdict.CAUGHT_UP).getOrThrow()

        val stored = h.repository.decisions.value.getValue(endedShow.id)
        val stamp = assertNotNull(h.collection.summaries.value.single { it.mediaId == endedShow.id }.addedAtEpochMs)
        assertEquals(stamp, stored.entryAddedAtEpochMs)
    }

    @Test
    fun a_claim_with_no_stamp_never_removes_an_entry() = runTest {
        // A row written before the stamp column existed: it claims an entry but cannot prove the entry is still that one.
        val h = harness(endedDetails)
        h.repository.record(TriageDecision.of(endedShow, TriageVerdict.CAUGHT_UP, NOW_EPOCH_MS, resolved = true, createdEntry = true))
        h.collection.add(endedDetails)

        h.undo(endedShow.id, TriageVerdict.CAUGHT_UP)

        assertTrue(h.entryPresent(endedShow.id))
        assertEquals(emptyList(), h.collection.removed)
    }

    // --- #790: undo reverses the ticks the verdict wrote and nothing else ---

    @Test
    fun undo_unticks_only_the_episode_the_watching_verdict_ticked_and_keeps_the_persons_own_tick() = runTest {
        val h = harness(ongoingDetails)
        h.collection.add(ongoingDetails)
        val s1e1 = EpisodeId(ongoingShow.id, seasonNumber = 1, episodeNumber = 1)
        val s1e2 = EpisodeId(ongoingShow.id, seasonNumber = 1, episodeNumber = 2)
        // The person ticked episode 2 from Detail before the deck offered the title.
        h.progress.seedSeen(s1e2)

        h.record(ongoingShow, TriageVerdict.WATCHING).getOrThrow()
        assertEquals(setOf(s1e1, s1e2), h.progress.seen.value)

        h.undo(ongoingShow.id, TriageVerdict.WATCHING)

        assertEquals(setOf(s1e2), h.progress.seen.value, "the verdict's tick goes, the person's stays")
        assertTrue(h.entryPresent(ongoingShow.id), "the entry was already in the library, so it stays")
    }

    @Test
    fun undo_of_caught_up_on_a_title_in_the_library_keeps_an_episode_the_person_already_ticked() = runTest {
        val h = harness(ongoingDetails)
        h.collection.add(ongoingDetails)
        val s1e1 = EpisodeId(ongoingShow.id, seasonNumber = 1, episodeNumber = 1)
        val s1e2 = EpisodeId(ongoingShow.id, seasonNumber = 1, episodeNumber = 2)
        h.progress.seedSeen(s1e1)

        h.record(ongoingShow, TriageVerdict.CAUGHT_UP).getOrThrow()
        // Only the unseen aired episode is the verdict's; the one the person ticked is not recorded as its own.
        assertEquals(listOf(s1e2), h.repository.decisions.value.getValue(ongoingShow.id).tickedEpisodeIds)

        h.undo(ongoingShow.id, TriageVerdict.CAUGHT_UP)

        assertEquals(setOf(s1e1), h.progress.seen.value)
    }

    @Test
    fun a_verdict_records_every_episode_it_newly_ticked() = runTest {
        val h = harness(ongoingDetails)

        h.record(ongoingShow, TriageVerdict.CAUGHT_UP).getOrThrow()

        // Aired episodes only: the unaired episode 3 is never ticked (ADR 0005, EpisodeOrdering.airedBy).
        assertEquals(
            listOf(EpisodeId(ongoingShow.id, 1, 1), EpisodeId(ongoingShow.id, 1, 2)),
            h.repository.decisions.value.getValue(ongoingShow.id).tickedEpisodeIds,
        )
    }

    @Test
    fun undo_of_a_movie_caught_up_on_a_title_in_the_library_unticks_the_movie_tick() = runTest {
        val h = harness(movieDetails(film))
        h.collection.add(movieDetails(film))

        h.record(film, TriageVerdict.CAUGHT_UP).getOrThrow()
        assertEquals(setOf(EpisodeId.forMovie(film.id)), h.progress.seen.value)

        h.undo(film.id, TriageVerdict.CAUGHT_UP)

        assertEquals(emptySet(), h.progress.seen.value)
        assertTrue(h.entryPresent(film.id))
    }

    // --- #791: the claim is read right before the add, and a retry claims what its own add makes ---

    @Test
    fun a_title_added_from_detail_during_the_fetch_is_not_claimed_by_the_verdict() = runTest {
        val gate = CompletableDeferred<Unit>()
        val h = Harness(details = GatedDetailsSource(endedDetails, gate))

        val verdict = launch { h.record(endedShow, TriageVerdict.CAUGHT_UP) }
        runCurrent()
        // The person adds the title while the verdict's details are still in flight.
        h.collection.add(endedDetails)
        gate.complete(Unit)
        verdict.join()

        assertFalse(h.repository.decisions.value.getValue(endedShow.id).createdEntry)
        h.undo(endedShow.id, TriageVerdict.CAUGHT_UP)
        assertTrue(h.entryPresent(endedShow.id), "the entry the person added during the fetch survives undo")
    }

    @Test
    fun a_retry_whose_first_attempt_failed_before_its_add_claims_the_entry_its_add_makes() = runTest {
        val failing = Harness(details = FakeTriageDetailsSource(failure = IllegalStateException("offline")))
        failing.record(endedShow, TriageVerdict.CAUGHT_UP)
        assertFalse(failing.repository.decisions.value.getValue(endedShow.id).createdEntry)

        val retrying = RecordDecisionUseCase(
            failing.repository,
            failing.collection,
            failing.progress,
            FakeTriageDetailsSource(byId = mapOf(endedShow.id to endedDetails)),
            FakeClock(NOW_EPOCH_MS),
        )
        retrying.retry(failing.repository.decisions.value.getValue(endedShow.id)).getOrThrow()
        assertTrue(failing.repository.decisions.value.getValue(endedShow.id).createdEntry)

        failing.undo(endedShow.id, TriageVerdict.CAUGHT_UP)
        assertFalse(failing.entryPresent(endedShow.id))
    }

    // --- #792: an undo and a verdict on the same title do not interleave ---

    @Test
    fun undo_during_an_in_flight_verdict_leaves_the_title_out_of_the_library() = runTest {
        val gate = CompletableDeferred<Unit>()
        val h = Harness(details = GatedDetailsSource(endedDetails, gate))

        val verdict = launch { h.record(endedShow, TriageVerdict.CAUGHT_UP) }
        runCurrent()
        // The person swipes Undo while the details are still being fetched. Undo waits for the verdict to finish.
        val undo = launch { h.undo(endedShow.id, TriageVerdict.CAUGHT_UP) }
        runCurrent()
        gate.complete(Unit)
        joinAll(verdict, undo)

        assertFalse(h.entryPresent(endedShow.id))
        assertEquals(emptyMap(), h.repository.decisions.value)
        assertEquals(emptySet(), h.progress.seen.value)
    }

    @Test
    fun a_decision_restored_while_its_fetch_is_pending_writes_nothing() = runTest {
        val gate = CompletableDeferred<Unit>()
        val h = Harness(details = GatedDetailsSource(endedDetails, gate))

        val verdict = launch { h.record(endedShow, TriageVerdict.CAUGHT_UP) }
        runCurrent()
        // The Skipped screen restores the decision directly; it takes no lock, so the apply sees it gone.
        RestoreDecisionUseCase(h.repository)(endedShow.id)
        gate.complete(Unit)
        verdict.join()

        assertFalse(h.entryPresent(endedShow.id))
        assertEquals(emptyList(), h.collection.added)
        assertEquals(emptySet(), h.progress.seen.value)
        assertEquals(emptyMap(), h.repository.decisions.value)
    }
}
