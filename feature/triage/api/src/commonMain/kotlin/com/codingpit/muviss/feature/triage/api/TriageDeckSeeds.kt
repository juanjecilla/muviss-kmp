package com.codingpit.muviss.feature.triage.api

import com.codingpit.muviss.models.MediaSummary

/**
 * Hands Triage a deck of titles to swipe through, instead of the catalogue (EPIC 51, #554). Next Pick's Deck
 * result calls [seed] and then opens the seeded deck. It is a seam of its own rather than a method on
 * [TriageApi], so that a fake of [TriageApi] does not have to grow it.
 */
interface TriageDeckSeeds {
    /**
     * Replaces any earlier handoff with [cards], in the order given: the engine's rank order is kept, and a
     * seeded deck reads no catalogue. The seeds are held in memory only. A process death leaves the seeded
     * deck with nothing to offer, which it says rather than falling back to the catalogue.
     */
    fun seed(cards: List<MediaSummary>)
}
