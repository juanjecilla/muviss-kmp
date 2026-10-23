package com.codingpit.muviss.feature.nextpick.domain

import com.codingpit.muviss.feature.nextpick.api.PickAnchor
import com.codingpit.muviss.feature.nextpick.api.PickCommitment
import com.codingpit.muviss.feature.nextpick.api.PickCompany
import com.codingpit.muviss.feature.nextpick.api.PickCriteria
import com.codingpit.muviss.feature.nextpick.api.PickEra
import com.codingpit.muviss.feature.nextpick.api.PickMood
import com.codingpit.muviss.feature.nextpick.api.PickRuntime
import com.codingpit.muviss.feature.nextpick.api.PickSource
import com.codingpit.muviss.models.MediaType

/**
 * The catalogue of things a Pick Session can ask (EPIC 51, ADR 0032), in the order it asks them.
 * Which of them a given session actually asks is [PickSession]'s business; this is only the list.
 */
enum class PickQuestion {
    /** My list, something new, or either. */
    SOURCE,

    /** Movie or show, and how much time. */
    FORMAT_AND_TIME,

    /** A mood, which the engine reads as a set of genres. */
    MOOD,

    /** More like the title just finished, or something different. */
    ANCHOR,

    /** New or classic, original language, only my streaming services. */
    ERA_AND_SERVICES,

    /** Alone, with a partner, with family. */
    COMPANY,

    /** For shows: limited series, an ended show, an ongoing one. */
    COMMITMENT,

    /** Whether something already watched may come up again. */
    REWATCH,
}

/**
 * One answer, and what it adds to the [PickCriteria]. A sealed type rather than a map so a screen
 * cannot answer a question with another question's shape. Skipping is not an answer: it adds nothing.
 */
sealed interface PickAnswer {
    /** The question this answers. */
    val question: PickQuestion

    /** [criteria] with this answer applied. */
    fun applyTo(criteria: PickCriteria): PickCriteria

    data class Source(val source: PickSource) : PickAnswer {
        override val question = PickQuestion.SOURCE
        override fun applyTo(criteria: PickCriteria) = criteria.copy(source = source)
    }

    /** Either part may be null: "a movie, any length" and "any format, an evening" are both fine. */
    data class FormatAndTime(val type: MediaType?, val runtime: PickRuntime?) : PickAnswer {
        override val question = PickQuestion.FORMAT_AND_TIME
        override fun applyTo(criteria: PickCriteria) = criteria.copy(type = type, runtime = runtime)
    }

    data class Mood(val moods: Set<PickMood>) : PickAnswer {
        override val question = PickQuestion.MOOD
        override fun applyTo(criteria: PickCriteria) = criteria.copy(moods = moods)
    }

    data class Anchor(val anchor: PickAnchor) : PickAnswer {
        override val question = PickQuestion.ANCHOR
        override fun applyTo(criteria: PickCriteria) = criteria.copy(anchor = anchor)
    }

    data class EraAndServices(val era: PickEra?, val originalLanguage: String?, val onlyMyServices: Boolean) : PickAnswer {
        override val question = PickQuestion.ERA_AND_SERVICES
        override fun applyTo(criteria: PickCriteria) = criteria.copy(era = era, originalLanguage = originalLanguage, onlyMyServices = onlyMyServices)
    }

    data class Company(val company: PickCompany) : PickAnswer {
        override val question = PickQuestion.COMPANY
        override fun applyTo(criteria: PickCriteria) = criteria.copy(company = company)
    }

    data class Commitment(val commitment: PickCommitment) : PickAnswer {
        override val question = PickQuestion.COMMITMENT
        override fun applyTo(criteria: PickCriteria) = criteria.copy(commitment = commitment)
    }

    data class Rewatch(val ok: Boolean) : PickAnswer {
        override val question = PickQuestion.REWATCH
        override fun applyTo(criteria: PickCriteria) = criteria.copy(rewatchOk = ok)
    }
}
