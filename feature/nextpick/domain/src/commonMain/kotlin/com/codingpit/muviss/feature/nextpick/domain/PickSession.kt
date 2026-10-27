package com.codingpit.muviss.feature.nextpick.domain

import com.codingpit.muviss.feature.nextpick.api.PickCriteria
import com.codingpit.muviss.feature.nextpick.api.PickSource
import com.codingpit.muviss.feature.nextpick.api.SessionLength
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType

/**
 * What the session needs to know about the person and the moment to decide what is worth asking.
 * Everything here is read once when the session opens; nothing in it is stored back.
 */
data class PickContext(
    val length: SessionLength = SessionLength.DEFAULT,
    /** False offline: only the library can answer, so the session says so and asks nothing about the catalogue. */
    val online: Boolean = true,
    /** Whether the library has anything unstarted to offer. Without it "my list" has no answer. */
    val hasBacklog: Boolean = true,
    /** Whether anything in the library has been watched, which is what "a rewatch is fine" needs. */
    val hasWatchedTitles: Boolean = false,
    /** The title that was just finished, when the session follows a Completion: what "more like this" is about. */
    val anchorCandidate: MediaId? = null,
    /** Whether the person's streaming services are known. If not, "only my services" has no meaning and is not offered. */
    val servicesKnown: Boolean = false,
    /** The questions a filled Taste Profile (#402) already answers, so the session does not ask them again. */
    val answeredByProfile: Set<PickQuestion> = emptySet(),
    /** The criteria that profile contributes before the first question. */
    val startingCriteria: PickCriteria = PickCriteria(),
)

/**
 * A Pick Session as a value (EPIC 51, ADR 0032): the adaptive run of questions that ends in a
 * [PickCriteria]. Pure and deterministic: the same context and the same answers always give the same
 * questions in the same order, which is what makes the flow testable without a screen.
 *
 * Every question is skippable and a skipped one adds no constraint. A skip still counts against the
 * length, since the person was shown it. A question is left out entirely when its answer could not
 * change the result: offline, with an empty backlog, once "my list" makes the catalogue irrelevant, or
 * when the profile already answers it. Answers live in this object only; nothing is stored.
 */
class PickSession private constructor(
    private val context: PickContext,
    val criteria: PickCriteria,
    private val shown: Int,
    private val settled: Set<PickQuestion>,
) {

    /** The question to show now, or null when the session is over and [criteria] is final. */
    val question: PickQuestion? get() = if (shown >= context.length.maxQuestions) null else nextEligible()

    val isComplete: Boolean get() = question == null

    /** Whether "only my streaming services" is worth offering inside [PickQuestion.ERA_AND_SERVICES]. */
    val offersServices: Boolean get() = context.servicesKnown

    /** How many questions have been shown so far (answered or skipped). */
    val asked: Int get() = shown

    /** Records [answer] to the current question. An answer to a different question is a programming error. */
    fun answer(answer: PickAnswer): PickSession {
        val current = requireNotNull(question) { "the session is complete" }
        require(answer.question == current) { "asked $current, got ${answer.question}" }
        return advance(answer.applyTo(criteria), current)
    }

    /** Skips the current question: no constraint, one slot spent. */
    fun skip(): PickSession = advance(criteria, requireNotNull(question) { "the session is complete" })

    private fun advance(next: PickCriteria, done: PickQuestion) = PickSession(context, next, shown + 1, settled + done)

    private fun nextEligible(): PickQuestion? = PickQuestion.entries.firstOrNull { it !in settled && it !in context.answeredByProfile && isWorthAsking(it) }

    private fun isWorthAsking(question: PickQuestion): Boolean = when (question) {
        PickQuestion.SOURCE -> context.online && context.hasBacklog
        PickQuestion.FORMAT_AND_TIME, PickQuestion.MOOD, PickQuestion.COMPANY -> true
        PickQuestion.ANCHOR -> context.online && context.anchorCandidate != null && criteria.source != PickSource.MY_LIST
        PickQuestion.ERA_AND_SERVICES -> context.online && criteria.source != PickSource.MY_LIST
        PickQuestion.COMMITMENT -> criteria.type != MediaType.MOVIE
        PickQuestion.REWATCH -> context.hasWatchedTitles && criteria.source != PickSource.SOMETHING_NEW
    }

    companion object {
        /**
         * Opens a session. The source is decided up front when no question can: offline there is only the
         * library, and with an empty library there is only the catalogue.
         */
        fun start(context: PickContext): PickSession {
            // The sets may be the caller's mutable ones; the session must not change under its reader.
            val owned = context.copy(
                answeredByProfile = context.answeredByProfile.toSet(),
                startingCriteria = context.startingCriteria.copy(moods = context.startingCriteria.moods.toSet()),
            )
            var criteria = owned.startingCriteria
            if (!context.online) {
                criteria = criteria.copy(source = PickSource.MY_LIST)
            } else if (!context.hasBacklog) {
                criteria = criteria.copy(source = PickSource.SOMETHING_NEW)
            }
            return PickSession(owned, criteria, shown = 0, settled = emptySet())
        }
    }
}
