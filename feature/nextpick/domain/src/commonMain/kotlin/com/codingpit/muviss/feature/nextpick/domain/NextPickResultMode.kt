package com.codingpit.muviss.feature.nextpick.domain

import com.codingpit.muviss.feature.nextpick.api.ResultMode

/**
 * How a Pick Session's result is presented (EPIC 51, ADR 0032). The engine's output is the same for every
 * mode; a mode only decides how much of it one screen draws. The values are spelled as [ResultMode] so that
 * the persisted choice maps across by name, and [presentation] is the one place that maps it.
 *
 * [pickCount] is how many picks one screen draws from the engine's ranked list: One Pick draws the first and
 * "another" steps down the list; Top Picks and Deck draw the top [PickEngine.DEFAULT_LIMIT].
 */
enum class NextPickResultMode(val pickCount: Int) {
    ONE_PICK(1),
    TOP_PICKS(PickEngine.DEFAULT_LIMIT),
    DECK(PickEngine.DEFAULT_LIMIT),
}

/** The presentation the person chose in Settings (#657). Read when a session opens, so a change never reshuffles one on screen. */
fun ResultMode.presentation(): NextPickResultMode = when (this) {
    ResultMode.ONE_PICK -> NextPickResultMode.ONE_PICK
    ResultMode.TOP_PICKS -> NextPickResultMode.TOP_PICKS
    ResultMode.DECK -> NextPickResultMode.DECK
}
