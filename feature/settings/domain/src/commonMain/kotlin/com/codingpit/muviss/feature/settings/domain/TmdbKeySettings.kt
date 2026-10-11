package com.codingpit.muviss.feature.settings.domain

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
 * The Own TMDB Key as Settings sees it (ADR 0027). The key itself never crosses
 * this seam outward: a screen can set it and clear it, and learn only whose
 * credential is in use.
 */
interface TmdbKeySettings {
    fun observeSource(): Flow<TmdbDataSource>

    /** Checks [raw] with TMDB and keeps it only if TMDB accepts it. */
    suspend fun save(raw: String): SaveTmdbKeyResult

    /** Back to Muviss's credential. */
    suspend fun clear()
}

class ObserveTmdbDataSourceUseCase(private val settings: TmdbKeySettings) {
    operator fun invoke(): Flow<TmdbDataSource> = settings.observeSource()
}

class SaveTmdbKeyUseCase(private val settings: TmdbKeySettings) {
    suspend operator fun invoke(raw: String): SaveTmdbKeyResult = settings.save(raw)
}

class ClearTmdbKeyUseCase(private val settings: TmdbKeySettings) {
    suspend operator fun invoke() = settings.clear()
}
