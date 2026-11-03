package com.codingpit.muviss.feature.nextpick.domain

/**
 * How a Pick Session's result is presented (EPIC 51, #401 adds Top Picks and Deck). Lives beside the
 * settings seam rather than in `:api`, which #403 owns; a presentation is a UI choice, not a contract
 * a peer needs.
 */
enum class NextPickResultMode { ONE_PICK, }

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
