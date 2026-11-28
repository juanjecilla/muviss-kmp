package com.codingpit.muviss.feature.triage.domain

import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.feature.progress.api.EpisodePlay
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
        assertEquals(listOf(EpisodePlay.idOf(s1e2, NOW_EPOCH_MS)), h.repository.decisions.value.getValue(ongoingShow.id).tickedPlayIds)

        h.undo(ongoingShow.id, TriageVerdict.CAUGHT_UP)

        assertEquals(setOf(s1e1), h.progress.seen.value)
    }

    @Test
    fun a_verdict_records_every_episode_it_newly_ticked() = runTest {
        val h = harness(ongoingDetails)

        h.record(ongoingShow, TriageVerdict.CAUGHT_UP).getOrThrow()

        // Aired episodes only: the unaired episode 3 is never ticked (ADR 0005, EpisodeOrdering.airedBy).
        assertEquals(
            listOf(
                EpisodePlay.idOf(EpisodeId(ongoingShow.id, 1, 1), NOW_EPOCH_MS),
                EpisodePlay.idOf(EpisodeId(ongoingShow.id, 1, 2), NOW_EPOCH_MS),
            ),
            h.repository.decisions.value.getValue(ongoingShow.id).tickedPlayIds,
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

    // --- #790: undo takes back the viewings the verdict wrote, not the episode's history ---

    private val firstEpisode = EpisodeId(ongoingShow.id, seasonNumber = 1, episodeNumber = 1)

    /** A rewatch is a day after the verdict, so its viewing id never collides with the verdict's. */
    private val rewatchAt = NOW_EPOCH_MS + 86_400_000L

    @Test
    fun tick_then_rewatch_then_undo_keeps_the_rewatch_and_the_episode_stays_seen() = runTest {
        val h = harness(ongoingDetails)
        h.collection.add(ongoingDetails)
        h.record(ongoingShow, TriageVerdict.WATCHING).getOrThrow()
        // The person watches the same episode again, after the verdict ticked it.
        h.progress.recordRewatch(firstEpisode, rewatchAt)

        h.undo(ongoingShow.id, TriageVerdict.WATCHING)

        assertEquals(
            mapOf(EpisodePlay.idOf(firstEpisode, rewatchAt) to firstEpisode),
            h.progress.plays.value,
            "only the verdict's viewing goes; the rewatch is the person's and stays",
        )
        assertTrue(firstEpisode in h.progress.seen.value, "an episode with a rewatch left is still seen")
    }

    @Test
    fun undo_with_no_rewatch_removes_the_verdicts_viewing_and_unticks_the_episode() = runTest {
        val h = harness(ongoingDetails)
        h.collection.add(ongoingDetails)
        h.record(ongoingShow, TriageVerdict.WATCHING).getOrThrow()

        h.undo(ongoingShow.id, TriageVerdict.WATCHING)

        assertEquals(emptyMap(), h.progress.plays.value)
        assertFalse(firstEpisode in h.progress.seen.value)
    }

    @Test
    fun undo_of_caught_up_keeps_a_rewatch_of_one_of_the_episodes_it_ticked() = runTest {
        val h = harness(ongoingDetails)
        h.collection.add(ongoingDetails)
        val secondEpisode = EpisodeId(ongoingShow.id, seasonNumber = 1, episodeNumber = 2)
        h.record(ongoingShow, TriageVerdict.CAUGHT_UP).getOrThrow()
        h.progress.recordRewatch(secondEpisode, rewatchAt)

        h.undo(ongoingShow.id, TriageVerdict.CAUGHT_UP)

        assertEquals(setOf(EpisodePlay.idOf(secondEpisode, rewatchAt)), h.progress.plays.value.keys)
        assertEquals(setOf(secondEpisode), h.progress.seen.value)
    }

    @Test
    fun legacy_bare_episode_ids_are_no_ops_and_keep_a_rewatch() = runTest {
        val h = harness(ongoingDetails)
        h.collection.add(ongoingDetails)
        // A decision whose list holds a bare episode id, the shape written before viewing ids. Undo must not read it
        // as a viewing and must not take the person's rewatch with it.
        h.progress.recordRewatch(firstEpisode, rewatchAt)
        h.repository.record(
            TriageDecision.of(ongoingShow, TriageVerdict.WATCHING, NOW_EPOCH_MS, resolved = true)
                .copy(tickedPlayIds = listOf(firstEpisode.toString())),
        )

        h.undo(ongoingShow.id, TriageVerdict.WATCHING)

        assertEquals(mapOf(EpisodePlay.idOf(firstEpisode, rewatchAt) to firstEpisode), h.progress.plays.value)
        assertTrue(firstEpisode in h.progress.seen.value)
    }

    @Test
    fun a_replayed_removal_of_an_already_removed_viewing_does_not_reach_a_later_rewatch() = runTest {
        val h = harness(ongoingDetails)
        h.collection.add(ongoingDetails)
        h.record(ongoingShow, TriageVerdict.WATCHING).getOrThrow()
        val verdictViewing = h.repository.decisions.value.getValue(ongoingShow.id).tickedPlayIds.single()
        // An earlier undo took the verdict's viewing back. The person then rewatches. A replay of that removal must
        // find nothing to remove.
        h.progress.removePlay(verdictViewing)
        h.progress.recordRewatch(firstEpisode, rewatchAt)

        h.progress.removePlay(verdictViewing)

        assertEquals(mapOf(EpisodePlay.idOf(firstEpisode, rewatchAt) to firstEpisode), h.progress.plays.value)
        assertTrue(firstEpisode in h.progress.seen.value)
    }

    @Test
    fun two_devices_undo_of_a_verdict_made_on_the_other_keeps_this_devices_own_rewatch() = runTest {
        // Device A made the verdict. Its decision reached device B by sync, and so did the viewing the verdict wrote.
        val a = harness(ongoingDetails)
        a.collection.add(ongoingDetails)
        a.record(ongoingShow, TriageVerdict.WATCHING).getOrThrow()
        val decisionFromA = a.repository.decisions.value.getValue(ongoingShow.id)

        // Device B holds the same viewing (same id) and a rewatch it recorded itself.
        val b = harness(ongoingDetails)
        b.collection.add(ongoingDetails)
        b.progress.recordFirstPlays(listOf(firstEpisode), NOW_EPOCH_MS)
        b.progress.recordRewatch(firstEpisode, rewatchAt)
        b.repository.record(decisionFromA)

        b.undo(ongoingShow.id, TriageVerdict.WATCHING)

        assertEquals(mapOf(EpisodePlay.idOf(firstEpisode, rewatchAt) to firstEpisode), b.progress.plays.value)
        assertTrue(firstEpisode in b.progress.seen.value)
    }

    // --- #790 on the owned path: an entry the verdict created is undone by its viewings too ---

    @Test
    fun owned_entry_with_a_later_rewatch_keeps_the_rewatch_and_the_entry() = runTest {
        val h = harness(ongoingDetails)
        h.record(ongoingShow, TriageVerdict.WATCHING).getOrThrow() // the verdict creates the entry
        h.progress.recordRewatch(firstEpisode, rewatchAt)

        h.undo(ongoingShow.id, TriageVerdict.WATCHING)

        assertEquals(mapOf(EpisodePlay.idOf(firstEpisode, rewatchAt) to firstEpisode), h.progress.plays.value)
        assertTrue(firstEpisode in h.progress.seen.value, "the rewatch keeps the episode seen")
        assertTrue(h.entryPresent(ongoingShow.id), "the person watched the title since the verdict, so the entry stays")
        assertEquals(emptyList(), h.progress.cleared, "no path clears the whole title any more")
    }

    @Test
    fun owned_entry_with_no_rewatch_is_removed_with_only_the_verdicts_viewings() = runTest {
        val h = harness(ongoingDetails)
        h.record(ongoingShow, TriageVerdict.WATCHING).getOrThrow()

        h.undo(ongoingShow.id, TriageVerdict.WATCHING)

        assertFalse(h.entryPresent(ongoingShow.id))
        assertEquals(emptyMap(), h.progress.plays.value)
        assertFalse(firstEpisode in h.progress.seen.value)
        assertEquals(emptyList(), h.progress.cleared)
    }

    @Test
    fun owned_entry_keeps_progress_that_was_on_the_title_before_the_verdict() = runTest {
        val h = harness(ongoingDetails)
        // Progress from an earlier life of the title, written before the decision: the verdict did not write it.
        val beforeTheVerdict = NOW_EPOCH_MS - 1_000L
        h.progress.recordRewatch(firstEpisode, beforeTheVerdict)
        h.record(ongoingShow, TriageVerdict.CAUGHT_UP).getOrThrow() // ticks only the second aired episode

        h.undo(ongoingShow.id, TriageVerdict.CAUGHT_UP)

        assertEquals(mapOf(EpisodePlay.idOf(firstEpisode, beforeTheVerdict) to firstEpisode), h.progress.plays.value)
        assertEquals(setOf(firstEpisode), h.progress.seen.value)
        assertFalse(h.entryPresent(ongoingShow.id), "no viewing was made since the decision, so the entry the verdict made goes")
    }

    @Test
    fun owned_entry_retried_then_undone_with_no_rewatch_is_removed() = runTest {
        val h = harness(ongoingDetails)
        h.record(ongoingShow, TriageVerdict.WATCHING).getOrThrow()
        h.record.retry(h.repository.decisions.value.getValue(ongoingShow.id)).getOrThrow()

        h.undo(ongoingShow.id, TriageVerdict.WATCHING)

        assertFalse(h.entryPresent(ongoingShow.id))
        assertEquals(emptyMap(), h.progress.plays.value)
    }

    @Test
    fun owned_entry_retried_then_undone_with_a_later_rewatch_keeps_the_rewatch_and_the_entry() = runTest {
        val h = harness(ongoingDetails)
        h.record(ongoingShow, TriageVerdict.WATCHING).getOrThrow()
        h.record.retry(h.repository.decisions.value.getValue(ongoingShow.id)).getOrThrow()
        h.progress.recordRewatch(firstEpisode, rewatchAt)

        h.undo(ongoingShow.id, TriageVerdict.WATCHING)

        assertEquals(mapOf(EpisodePlay.idOf(firstEpisode, rewatchAt) to firstEpisode), h.progress.plays.value)
        assertTrue(h.entryPresent(ongoingShow.id))
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

    // --- #902: a retry keeps the stamp of the entry its claim is about, never another entry's ---

    @Test
    fun a_retry_against_the_original_entry_keeps_its_stamp() = runTest {
        val h = harness(endedDetails)
        h.record(endedShow, TriageVerdict.CAUGHT_UP).getOrThrow()
        val first = h.repository.decisions.value.getValue(endedShow.id)

        h.record.retry(first).getOrThrow()

        val retried = h.repository.decisions.value.getValue(endedShow.id)
        assertTrue(retried.createdEntry)
        assertEquals(assertNotNull(first.entryAddedAtEpochMs), retried.entryAddedAtEpochMs)
    }

    @Test
    fun a_retry_after_the_person_removed_the_entry_and_added_a_replacement_keeps_the_original_stamp() = runTest {
        val h = harness(endedDetails)
        h.record(endedShow, TriageVerdict.CAUGHT_UP).getOrThrow()
        val originalStamp = assertNotNull(h.repository.decisions.value.getValue(endedShow.id).entryAddedAtEpochMs)
        // The verdict's entry is removed, and the person adds the title back: a replacement with a new stamp.
        h.collection.remove(endedShow.id)
        h.collection.add(endedDetails)
        val replacementStamp = assertNotNull(h.collection.summaries.value.single().addedAtEpochMs)
        assertFalse(originalStamp == replacementStamp)

        // A retry of the verdict, its earlier attempt having left ticks unfinished, runs against the replacement.
        h.record.retry(h.repository.decisions.value.getValue(endedShow.id)).getOrThrow()

        val retried = h.repository.decisions.value.getValue(endedShow.id)
        assertTrue(retried.createdEntry)
        assertEquals(originalStamp, retried.entryAddedAtEpochMs, "the claim stays on the verdict's own entry, not the replacement")

        h.undo(endedShow.id, TriageVerdict.CAUGHT_UP)

        assertTrue(h.entryPresent(endedShow.id), "undo must not remove the replacement the person added")
        assertEquals(listOf(endedShow.id), h.collection.removed, "only the person's own removal is on record")
    }

    @Test
    fun a_pre_stamp_claim_retried_against_a_replacement_leaves_the_replacement_alone() = runTest {
        val h = harness(endedDetails)
        // A row written before the stamp column: it claims an entry but has no stamp to prove which entry that is.
        h.repository.record(TriageDecision.of(endedShow, TriageVerdict.CAUGHT_UP, NOW_EPOCH_MS, resolved = false, createdEntry = true))
        // The person's entry is in the library when the retry runs, and it may be a replacement the person added.
        h.collection.add(endedDetails)

        h.record.retry(h.repository.decisions.value.getValue(endedShow.id)).getOrThrow()

        // The claim cannot name the entry, so the retry retires it rather than adopting the present entry's stamp.
        assertFalse(h.repository.decisions.value.getValue(endedShow.id).createdEntry)
        h.undo(endedShow.id, TriageVerdict.CAUGHT_UP)

        assertTrue(h.entryPresent(endedShow.id), "undo must not remove an entry the verdict did not claim")
        assertEquals(emptyList(), h.collection.removed)
    }

    @Test
    fun a_retry_after_the_entry_was_removed_and_nothing_re_added_claims_the_entry_its_add_makes() = runTest {
        val h = harness(endedDetails)
        h.record(endedShow, TriageVerdict.CAUGHT_UP).getOrThrow()
        val originalStamp = assertNotNull(h.repository.decisions.value.getValue(endedShow.id).entryAddedAtEpochMs)
        h.collection.remove(endedShow.id)

        // The retry's add is the one that makes the entry now, so the claim follows that entry's new stamp.
        h.record.retry(h.repository.decisions.value.getValue(endedShow.id)).getOrThrow()

        val retried = h.repository.decisions.value.getValue(endedShow.id)
        val currentStamp = assertNotNull(h.collection.summaries.value.single { it.mediaId == endedShow.id }.addedAtEpochMs)
        assertTrue(retried.createdEntry)
        assertEquals(currentStamp, retried.entryAddedAtEpochMs)
        assertFalse(originalStamp == currentStamp)
    }

    @Test
    fun a_retry_whose_claim_has_a_stamp_keeps_that_stamp_against_a_present_entry() = runTest {
        // The claim is written directly so its stamp is a value the test chooses: a present entry's stamp
        // (1000, from the fake) must never be read into a decision whose claim already names another stamp.
        val h = harness(endedDetails)
        h.repository.record(
            TriageDecision.of(endedShow, TriageVerdict.CAUGHT_UP, NOW_EPOCH_MS, resolved = false, createdEntry = true)
                .copy(entryAddedAtEpochMs = 42L),
        )
        h.collection.add(endedDetails) // a present entry, stamped 1000 by the fake

        h.record.retry(h.repository.decisions.value.getValue(endedShow.id)).getOrThrow()

        assertEquals(42L, h.repository.decisions.value.getValue(endedShow.id).entryAddedAtEpochMs)
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
