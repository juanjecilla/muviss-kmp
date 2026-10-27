package com.codingpit.muviss.feature.progress.domain

import com.codingpit.muviss.feature.progress.api.Completion
import com.codingpit.muviss.feature.progress.api.CompletionKind
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CompletionDetectorTest {

    private val show = MediaId.tmdbTv("1")
    private val movie = MediaId.tmdbMovie("2")

    private fun of(id: MediaId, before: WatchStatus?, after: WatchStatus?) = CompletionDetector.completionOf(id, before, after)

    @Test
    fun a_movie_going_from_not_started_to_watched_is_finished() {
        assertEquals(Completion(movie, CompletionKind.FINISHED), of(movie, WatchStatus.NOT_STARTED, WatchStatus.WATCHED))
    }

    @Test
    fun an_ended_show_reaching_finished_is_finished_from_either_incomplete_state() {
        assertEquals(Completion(show, CompletionKind.FINISHED), of(show, WatchStatus.WATCHING, WatchStatus.FINISHED))
        assertEquals(Completion(show, CompletionKind.FINISHED), of(show, WatchStatus.NOT_STARTED, WatchStatus.FINISHED))
    }

    @Test
    fun an_ongoing_show_reaching_watched_is_caught_up() {
        assertEquals(Completion(show, CompletionKind.CAUGHT_UP), of(show, WatchStatus.WATCHING, WatchStatus.WATCHED))
    }

    @Test
    fun a_title_already_complete_is_not_completed_again() {
        assertNull(of(show, WatchStatus.WATCHED, WatchStatus.FINISHED))
        assertNull(of(show, WatchStatus.WATCHED, WatchStatus.WATCHED))
        assertNull(of(show, WatchStatus.FINISHED, WatchStatus.FINISHED))
        assertNull(of(movie, WatchStatus.WATCHED, WatchStatus.WATCHED))
    }

    @Test
    fun a_title_that_is_not_complete_afterwards_is_not_a_completion() {
        assertNull(of(show, WatchStatus.NOT_STARTED, WatchStatus.WATCHING))
        assertNull(of(show, WatchStatus.WATCHING, WatchStatus.WATCHING))
        assertNull(of(show, WatchStatus.WATCHING, WatchStatus.NOT_STARTED))
    }

    @Test
    fun a_title_outside_the_library_has_no_status_and_so_no_completion() {
        assertNull(of(show, null, WatchStatus.FINISHED))
        assertNull(of(show, WatchStatus.WATCHING, null))
    }

    @Test
    fun unticking_reads_no_status_at_all() = runTest {
        var reads = 0
        val detector = CompletionDetector {
            reads++
            WatchStatus.WATCHING
        }
        var wrote = false
        detector.around(show, seen = false) { wrote = true }
        assertEquals(0, reads)
        assertEquals(true, wrote)
    }

    @Test
    fun a_status_that_cannot_be_read_still_lets_the_tick_through() = runTest {
        val detector = CompletionDetector { error("seenEpisodes > airedEpisodes on another title") }
        var wrote = false
        detector.around(show, seen = true) { wrote = true }
        assertEquals(true, wrote)
    }

    @Test
    fun two_overlapping_ticks_that_complete_a_show_emit_one_completion() = runTest {
        // Two unseen episodes left; the show is Watched once both are ticked.
        var unseen = 2
        // The read suspends, as the real one does on the database, so the two ticks can interleave.
        val detector = CompletionDetector {
            yield()
            if (unseen == 0) WatchStatus.WATCHED else WatchStatus.WATCHING
        }
        val seen = mutableListOf<Completion>()
        val collecting = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { detector.completions.toList(seen) }

        // Both writes wait on one gate, so unserialized both "before" reads see Watching and both
        // "after" reads (each yielding past the other tick's write) see Watched.
        val gate = CompletableDeferred<Unit>()
        val tick: suspend () -> Unit = {
            gate.await()
            unseen--
        }
        val first = launch { detector.around(show, seen = true, tick) }
        val second = launch { detector.around(show, seen = true, tick) }
        testScheduler.runCurrent()
        gate.complete(Unit)
        first.join()
        second.join()

        assertEquals(listOf(Completion(show, CompletionKind.CAUGHT_UP)), seen)
        collecting.cancel()
    }
}
