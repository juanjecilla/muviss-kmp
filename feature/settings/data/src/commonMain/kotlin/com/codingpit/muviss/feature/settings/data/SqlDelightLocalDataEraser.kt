package com.codingpit.muviss.feature.settings.data

import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.secrets.SecretStore
import com.codingpit.muviss.core.common.widget.WidgetRefresher
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.core.network.tmdb.OwnTmdbKey
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
 * The home-screen widgets observe nothing (they render outside the app), so
 * they are told explicitly once everything is gone (#317), the same push
 * `SqlDelightProgressRepository` makes after every progress write.
 */
class SqlDelightLocalDataEraser(
    private val database: MuvissDatabase,
    private val dispatchers: AppDispatchers,
    private val ownTmdbKey: OwnTmdbKey,
    private val secrets: SecretStore,
    private val widgetRefresher: WidgetRefresher,
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
            // The last remote config (EPIC 45): a fresh install has none.
            database.remoteConfigCacheQueries.deleteAll()
            // Whose library this was, and how far it had pulled.
            database.syncCursorQueries.deleteAll()
            database.syncStateQueries.deleteRow()
        }
        // The Own TMDB Key (EPIC 46) is not in the database. `clear` also drops the copy the
        // provider holds in memory, so the next request is Muviss's; `clearAll` then wipes the
        // store and its key material, which on iOS is a Keychain item that outlives an uninstall.
        ownTmdbKey.clear()
        secrets.clearAll()
        widgetRefresher.refresh()
    }
}
