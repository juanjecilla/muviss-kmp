package com.codingpit.muviss.feature.triage.domain

import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.feature.triage.api.TriageVerdict
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.ProductionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What Undo takes back, and when (#790, #791, #792, #919). */
class TriageUndoTest {

    private val show = show("1399", "Game of Thrones")
    private val details = tvDetails(
        summary = show,
        seasons = listOf(1 to listOf(TODAY_EPOCH_DAY - 30, TODAY_EPOCH_DAY - 23, TODAY_EPOCH_DAY + 4)),
        productionStatus = ProductionStatus.RETURNING,
    )
    private val episode1 = EpisodeId(show.id, 1, 1)
    private val episode2 = EpisodeId(show.id, 1, 2)

    private class Harness(details: FakeTriageDetailsSource) {
        val repository = FakeTriageDecisionRepository()
        val collection = FakeCollectionApi()
        val progress = FakeProgressApi()
        val ledger = TriageUndoLedger()
        val record = RecordDecisionUseCase(repository, collection, progress, details, FakeClock(NOW_EPOCH_MS), ledger)
        val undo = UndoDecisionUseCase(repository, collection, progress, ledger)
    }

    private fun harness(whileFetching: suspend () -> Unit = {}) = Harness(
        FakeTriageDetailsSource(byId = mapOf(show.id to details), whileFetching = whileFetching),
    )

    // --- #790 + #919: only the verdict's own ticks are taken back ---

    @Test
    fun undo_takes_back_the_verdicts_ticks_on_an_entry_the_person_added_and_keeps_their_own() = runTest {
        val h = harness()
        h.collection.add(details)
        h.progress.setEpisodeSeen(episode1, seen = true)

        h.record(show, TriageVerdict.CAUGHT_UP).getOrThrow()
        assertEquals(setOf(episode1, episode2), h.progress.seen.value)
        h.undo(show.id, TriageVerdict.CAUGHT_UP)

        // Episode 1 was the person's; episode 2 was the verdict's. The entry is theirs and stays.
        assertEquals(setOf(episode1), h.progress.seen.value)
        assertEquals(listOf(episode2), h.progress.undone)
        assertEquals(emptyList(), h.collection.removed)
        assertEquals(emptyList(), h.progress.cleared)
    }

    @Test
    fun a_tick_the_person_makes_while_the_details_are_fetching_is_not_the_verdicts_to_undo() = runTest {
        lateinit var h: Harness
        h = harness(whileFetching = { h.progress.setEpisodeSeen(episode2, seen = true) })
        h.collection.add(details)

        h.record(show, TriageVerdict.CAUGHT_UP).getOrThrow()
        h.undo(show.id, TriageVerdict.CAUGHT_UP)

        assertEquals(setOf(episode2), h.progress.seen.value)
        assertEquals(listOf(episode1), h.progress.undone)
    }

    @Test
    fun a_watching_verdict_on_an_episode_already_seen_leaves_nothing_to_undo() = runTest {
        val h = harness()
        h.collection.add(details)
        h.progress.setEpisodeSeen(episode1, seen = true)

        h.record(show, TriageVerdict.WATCHING).getOrThrow()
        h.undo(show.id, TriageVerdict.WATCHING)

        assertEquals(setOf(episode1), h.progress.seen.value)
        assertEquals(emptyList(), h.progress.undone)
    }

    @Test
    fun a_watching_verdict_that_ticked_the_first_episode_has_it_taken_back() = runTest {
        val h = harness()
        h.collection.add(details)

        h.record(show, TriageVerdict.WATCHING).getOrThrow()
        h.undo(show.id, TriageVerdict.WATCHING)

        assertEquals(emptySet(), h.progress.seen.value)
        assertEquals(listOf(episode1), h.progress.undone)
    }

    // --- #791: the claim is the library's state when the add lands ---

    @Test
    fun a_title_added_from_detail_during_the_fetch_is_not_claimed_by_the_verdict() = runTest {
        lateinit var h: Harness
        h = harness(whileFetching = { h.collection.add(details) })

        h.record(show, TriageVerdict.LATER).getOrThrow()
        assertFalse(h.repository.decisions.value.getValue(show.id).createdEntry)
        h.undo(show.id, TriageVerdict.LATER)

        assertEquals(emptyList(), h.collection.removed)
        assertTrue(h.collection.summaries.value.any { it.mediaId == show.id })
    }

    // --- #792: Undo while the verdict's write is in flight ---

    @Test
    fun undo_pressed_during_the_fetch_waits_for_the_write_and_leaves_the_title_out_of_the_library() = runTest {
        val release = CompletableDeferred<Unit>()
        val h = harness(whileFetching = { release.await() })

        val recording = launch { h.record(show, TriageVerdict.CAUGHT_UP) }
        runCurrent()
        val undoing = launch { h.undo(show.id, TriageVerdict.CAUGHT_UP) }
        runCurrent()
        // The write has not landed, and the undo has not run ahead of it.
        assertEquals(emptyList(), h.collection.removed)

        release.complete(Unit)
        advanceUntilIdle()
        recording.join()
        undoing.join()

        assertTrue(h.collection.summaries.value.none { it.mediaId == show.id })
        assertEquals(emptySet(), h.progress.seen.value)
        assertEquals(listOf(show.id), h.repository.restored)
    }

    @Test
    fun a_write_that_finds_its_decision_already_restored_writes_nothing() = runTest {
        lateinit var h: Harness
        h = harness(whileFetching = { h.repository.restore(show.id) })

        h.record(show, TriageVerdict.CAUGHT_UP).getOrThrow()

        assertEquals(emptyList(), h.collection.added)
        assertEquals(emptySet(), h.progress.seen.value)
    }
}
