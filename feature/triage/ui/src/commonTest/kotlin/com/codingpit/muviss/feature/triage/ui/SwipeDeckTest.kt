package com.codingpit.muviss.feature.triage.ui

import com.codingpit.muviss.core.common.flags.TriageControlScheme
import com.codingpit.muviss.feature.triage.api.TriageVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The two direction maps, which have to agree.
 *
 * [verdictFor] answers "this drag means what?", [directionFor] answers "this
 * verdict leaves which way?" — and the second is what the fly-out, the undo
 * re-entry and the button path all steer by. If they ever disagree, a card
 * dragged left would fly out to the right.
 */
class SwipeDeckTest {

    private val allVerdicts = TriageVerdict.entries.toList()

    @Test
    fun every_verdict_leaves_in_the_direction_that_commits_it() {
        DragDirection.entries.forEach { direction ->
            val verdict = verdictFor(direction, TriageControlScheme.FOUR_WAY, allVerdicts)
            assertEquals(direction, verdict?.let(::directionFor), "$direction round-trips to $verdict")
        }
    }

    @Test
    fun watching_still_drops_downward_under_the_three_way_scheme() {
        // The scheme decides which *drags* may commit Watching, not what
        // Watching looks like once its button has been pressed.
        assertNull(verdictFor(DragDirection.DOWN, TriageControlScheme.THREE_WAY, allVerdicts))
        assertEquals(DragDirection.DOWN, directionFor(TriageVerdict.WATCHING))
    }

    @Test
    fun a_verdict_the_card_does_not_offer_still_has_a_direction() {
        // A movie offers no Watching, so no drag commits it — but the map is
        // total, because nothing should have to handle a verdict with nowhere
        // to go.
        val movieVerdicts = TriageVerdict.availableFor(com.codingpit.muviss.models.MediaType.MOVIE)
        assertNull(verdictFor(DragDirection.DOWN, TriageControlScheme.FOUR_WAY, movieVerdicts))
        assertEquals(4, TriageVerdict.entries.map(::directionFor).distinct().size)
    }
}
