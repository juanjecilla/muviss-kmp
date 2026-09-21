package com.codingpit.muviss.feature.cowatch.ui

import kotlinx.serialization.Serializable

/**
 * Managing Companions — invite, accept, name, unlink. Reached from Profile,
 * beside the sync row, because that is where accounts already live.
 */
@Serializable
data object CompanionsRoute

/**
 * The Shortlist for one Companion. Reached from Progress, which is already the
 * "what do I watch" surface — but it answers a different question from
 * WatchNext and must read as clearly distinct from it (ADR 0022).
 */
@Serializable
data class ShortlistRoute(val companionUserId: String)
