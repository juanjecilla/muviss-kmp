package com.codingpit.muviss.feature.search.domain

import kotlinx.coroutines.flow.Flow

/**
 * Whether this device has seen the one-time Discover intro (EPIC 30, #73).
 * A per-device fact on `appSettings`, like triage's tutorial flag, so never synced.
 */
interface SearchOnboarding {
    fun observeIntroSeen(): Flow<Boolean>

    suspend fun setIntroSeen()
}
