package com.codingpit.muviss.feature.progress.domain

import com.codingpit.muviss.feature.progress.api.Completion
import com.codingpit.muviss.feature.progress.api.CompletionKind
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.test.runTest
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
}
