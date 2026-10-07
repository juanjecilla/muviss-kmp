package com.codingpit.muviss.feature.settings.data

import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.feature.settings.domain.LocalDataEraser
import kotlinx.coroutines.withContext

/**
 * Hard deletes, in one transaction, every row a fresh install would not have.
 *
 * Hard rather than the soft deletes the rest of the app writes: a tombstone is
 * a message to the other devices, and this is not one (see
 * [LocalDataEraser]). The singleton rows (`appSettings`, `profile`,
 * `syncState`) are deleted too; every reader runs its `ensureRow` first, so
 * they come back as their defaults on the next read.
 *
 * Every screen observes its tables, so nothing needs restarting: the Library
 * empties, Settings shows the defaults and Discover shows its first-run intro.
 */
class SqlDelightLocalDataEraser(
    private val database: MuvissDatabase,
    private val dispatchers: AppDispatchers,
) : LocalDataEraser {

    override suspend fun deleteAllData() = withContext(dispatchers.io) {
        database.transaction {
            // The user's own data — the set sync's "discard local data" deletes.
            database.collectionEntryQueries.deleteAll()
            database.episodeProgressQueries.deleteAll()
            database.episodePlayQueries.deleteAll()
            database.mediaListQueries.deleteAllLists()
            database.mediaListQueries.deleteAllEntries()
            database.triageDecisionQueries.deleteAll()
            database.triageSnoozeQueries.deleteAll()
            database.profileQueries.deleteRow()
            database.appSettingsQueries.deleteRow()
            // Co-watch's local copies (EPIC 41).
            database.companionLinkQueries.deleteAll()
            database.companionPoolQueries.deleteAllOut()
            database.companionPoolQueries.deleteAllIn()
            // Caches of TMDB's data.
            database.episodeQueries.deleteAll()
            database.titleWatchProvidersQueries.deleteAll()
            // Whose library this was, and how far it had pulled.
            database.syncCursorQueries.deleteAll()
            database.syncStateQueries.deleteRow()
        }
    }
}
