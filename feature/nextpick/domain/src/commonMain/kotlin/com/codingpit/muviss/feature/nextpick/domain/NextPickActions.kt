package com.codingpit.muviss.feature.nextpick.domain

import com.codingpit.muviss.models.MediaSummary

/**
 * What the three buttons on a Next Pick do (EPIC 51, ADR 0032). None of them writes a new kind of row:
 * Add is a CollectionEntry, Not for me is a TriageDecision Skip and Snooze is a triage Snooze (ADR 0023).
 * The implementation reaches those through the peers' `:api` modules only.
 */
interface NextPickActions {
    /** Saves the title to the library, as "Add" on any other surface does. */
    suspend fun add(summary: MediaSummary)

    /** Records a Skip, so the title also leaves the Discover deck. */
    suspend fun notForMe(summary: MediaSummary)

    /** Snoozes the title for a week, the default Snooze period. */
    suspend fun snooze(summary: MediaSummary)
}
