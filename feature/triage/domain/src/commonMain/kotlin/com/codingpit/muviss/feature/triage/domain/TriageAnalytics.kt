package com.codingpit.muviss.feature.triage.domain

import com.codingpit.muviss.core.common.analytics.AnalyticsEvent
import com.codingpit.muviss.feature.triage.api.TriageVerdict

/**
 * What triage would report, if anything were listening. Nothing is: the only
 * `AnalyticsTracker` binding is the no-op. These exist so the two control
 * schemes can be compared for real the day a vendor is chosen, without
 * re-deriving where the interesting moments are.
 */
sealed interface TriageEvent : AnalyticsEvent {

    data object DeckOpened : TriageEvent {
        override val name = "triage_deck_opened"
    }

    data class DecisionRecorded(
        val verdict: TriageVerdict,
        val viaGesture: Boolean,
        val controlScheme: String,
    ) : TriageEvent {
        override val name = "triage_decision_recorded"
        override val properties = mapOf(
            "verdict" to verdict.name,
            "input" to if (viaGesture) "gesture" else "button",
            "scheme" to controlScheme,
        )
    }

    /** The signal that a scheme causes mis-hits: how often a swipe is taken back. */
    data class DecisionUndone(val verdict: TriageVerdict) : TriageEvent {
        override val name = "triage_decision_undone"
        override val properties = mapOf("verdict" to verdict.name)
    }

    data object DeckExhausted : TriageEvent {
        override val name = "triage_deck_exhausted"
    }
}
