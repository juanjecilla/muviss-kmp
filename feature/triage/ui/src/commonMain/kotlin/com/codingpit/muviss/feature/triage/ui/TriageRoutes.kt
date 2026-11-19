package com.codingpit.muviss.feature.triage.ui

import kotlinx.serialization.Serializable

/** The card deck. Reached from Discover and from Settings — not a top-level destination. */
@Serializable
data object TriageRoute

/** The list of skipped titles, with a per-row restore. */
@Serializable
data object SkippedRoute

/** The list of postponed titles, with a per-row unsnooze and the date each returns. */
@Serializable
data object SnoozedRoute

/**
 * The deck Next Pick's Deck result opens (#554): the titles it was handed, not the catalogue. The screen is the same
 * [TriageRoute]'s; only the feed differs, and the ViewModel for it is the one bound under [SEEDED_DECK_QUALIFIER].
 */
@Serializable
data object SeededDeckRoute
