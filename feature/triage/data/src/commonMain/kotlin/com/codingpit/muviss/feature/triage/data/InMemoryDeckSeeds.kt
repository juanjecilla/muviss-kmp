package com.codingpit.muviss.feature.triage.data

import com.codingpit.muviss.feature.triage.api.TriageDeckSeeds
import com.codingpit.muviss.feature.triage.domain.DeckSeeds
import com.codingpit.muviss.models.MediaSummary

/**
 * The one handoff from Next Pick to a seeded deck (#554). Held in memory: a seeded deck is a session, and a process
 * death empties it, which the deck says rather than replaying the catalogue (#555). Written by [seed], read once per
 * seeded deck at construction, so a later handoff never changes a deck already on screen.
 */
class InMemoryDeckSeeds :
    TriageDeckSeeds,
    DeckSeeds {

    @Volatile
    private var cards: List<MediaSummary> = emptyList()

    override fun seed(cards: List<MediaSummary>) {
        this.cards = cards.toList()
    }

    override fun current(): List<MediaSummary> = cards
}
