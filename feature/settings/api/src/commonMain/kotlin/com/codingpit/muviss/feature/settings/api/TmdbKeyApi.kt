package com.codingpit.muviss.feature.settings.api

import kotlinx.coroutines.flow.Flow

/** Whose TMDB credential this device's requests are made under (EPIC 46). */
enum class TmdbDataSource { MUVISS, YOURS }

/** What came of saving a key. Only [SAVED] changes anything. */
enum class SaveTmdbKeyResult {
    SAVED,

    /** Neither a v4 read token nor a v3 key by shape; nothing was sent anywhere. */
    MALFORMED,

    /** TMDB said no. */
    REJECTED,

    /** Offline or TMDB failing: it could not be checked, so it was not saved. */
    COULD_NOT_CHECK,
}

/**
 * The Own TMDB Key as the rest of the app sees it (ADR 0027): Settings and Onboarding both set it through this. The key itself never crosses
 * this seam outward: a screen can set it and clear it, and learn only whose
 * credential is in use.
 */
interface TmdbKeyApi {
    fun observeSource(): Flow<TmdbDataSource>

    /** Checks [raw] with TMDB and keeps it only if TMDB accepts it. */
    suspend fun save(raw: String): SaveTmdbKeyResult

    /** Back to Muviss's credential. */
    suspend fun clear()
}
