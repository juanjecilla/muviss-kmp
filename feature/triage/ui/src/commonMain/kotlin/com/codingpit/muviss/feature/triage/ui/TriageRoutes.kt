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
