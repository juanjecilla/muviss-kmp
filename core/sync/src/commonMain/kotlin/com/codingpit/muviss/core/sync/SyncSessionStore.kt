package com.codingpit.muviss.core.sync

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.codingpit.muviss.core.database.SyncAccountQueries

/**
 * Persists the current [SyncSession] locally (`syncAccount`, see
 * `SyncAccount.sq`) so a cold app launch can present as still signed in
 * without a network round trip. A tiny interface (rather than handing
 * `SyncAccountQueries` straight to `SupabaseSyncBackend`) so a future
 * backend swap doesn't need to know this is backed by SQLDelight at all.
 */
interface SyncSessionStore {
    suspend fun load(): SyncSession?

    suspend fun save(session: SyncSession)

    suspend fun clear()
}

class SqlDelightSyncSessionStore(private val queries: SyncAccountQueries) : SyncSessionStore {

    override suspend fun load(): SyncSession? {
        queries.ensureRow()
        val row = queries.selectAccount().awaitAsOneOrNull() ?: return null
        val backendId = row.backendId?.let { runCatching { SyncBackendId.valueOf(it) }.getOrNull() }
        val userId = row.userId
        val accessToken = row.accessToken
        if (backendId == null || userId == null || accessToken == null) return null
        return SyncSession(
            backendId = backendId,
            userId = userId,
            email = row.email,
            accessToken = accessToken,
            refreshToken = row.refreshToken,
            expiresAtEpochMs = row.expiresAtEpochMs,
        )
    }

    override suspend fun save(session: SyncSession) {
        queries.ensureRow()
        queries.saveSession(
            backendId = session.backendId.name,
            userId = session.userId,
            email = session.email,
            accessToken = session.accessToken,
            refreshToken = session.refreshToken,
            expiresAtEpochMs = session.expiresAtEpochMs,
        )
    }

    override suspend fun clear() {
        queries.ensureRow()
        queries.clearSession()
    }
}
