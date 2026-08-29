package com.codingpit.muviss.feature.profile.ui

import kotlinx.serialization.Serializable

/** Type-safe navigation route for the profile feature. */
@Serializable
data object ProfileRoute

/**
 * The full rewatch ranking, opened from the profile's "Most rewatched" card.
 *
 * Profile's first sub-destination — the stats section is a summary, and a
 * ranking with two lists, a window control and a year of trend does not fit
 * in a card. Follows the same nested-route shape search uses for episode
 * detail.
 */
@Serializable
data object RewatchRoute
