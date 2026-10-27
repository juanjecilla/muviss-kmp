package com.codingpit.muviss.feature.nextpick.domain

/**
 * How a Pick Session's result is presented (EPIC 51, ADR 0032). The engine's output is the same for every
 * mode; a mode only decides how much of it one screen draws. The values are spelled as #543's `ResultMode`
 * so that its persisted choice maps across by name.
 *
 * [pickCount] is how many picks one screen draws from the engine's ranked list: One Pick draws the first and
 * "another" steps down the list; Top Picks and Deck draw the top [PickEngine.DEFAULT_LIMIT].
 */
enum class NextPickResultMode(val pickCount: Int) {
    ONE_PICK(1),
    TOP_PICKS(PickEngine.DEFAULT_LIMIT),
    DECK(PickEngine.DEFAULT_LIMIT),
}

/**
 * The session length and result mode the person chose (EPIC 51, #403). Until #403 lands, Next Pick
 * reads them through this seam, which carries the defaults and nothing else. Replacing the binding is
 * the whole of #403's wiring here.
 */
interface NextPickSettings {
    /** The length the session opens with. Default: [PickSessionLength.STANDARD]. */
    val length: PickSessionLength

    /** Which presentation shows the result. Default: [NextPickResultMode.ONE_PICK]. */
    val mode: NextPickResultMode
}

/** The defaults until #403. */
object DefaultNextPickSettings : NextPickSettings {
    override val length: PickSessionLength = PickSessionLength.STANDARD
    override val mode: NextPickResultMode = NextPickResultMode.ONE_PICK
}
