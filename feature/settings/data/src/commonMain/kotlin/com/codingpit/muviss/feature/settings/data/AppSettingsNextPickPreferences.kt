package com.codingpit.muviss.feature.settings.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.remoteconfig.RemoteConfig
import com.codingpit.muviss.core.database.AppSettingsQueries
import com.codingpit.muviss.feature.nextpick.api.NextPickPreferences
import com.codingpit.muviss.feature.nextpick.api.ResultMode
import com.codingpit.muviss.feature.nextpick.api.SessionLength
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import com.codingpit.muviss.core.database.AppSettings as AppSettingsRow

/**
 * The Next Pick preferences that are enums (EPIC 51, #403), read and written on the singleton `appSettings` row beside
 * [AppSettingsFeatureFlags]. Settings owns the row, so it owns this binding; `:feature:nextpick:api` owns the types.
 *
 * Same `ensureRow` discipline as the flags: the row is created before every read and write. A read that loses that race
 * falls back to the defaults the column declares, so it never shows a value the person did not choose.
 *
 * The result mode has one resolver, [resultMode]: the stored choice when there is one, else the served Remote Config
 * default (`nextPickDefaultResultMode`, #556), else [ResultMode.DEFAULT]. A row's `NULL` means "never chose" (#853), so
 * every writer of the row can create it, in any order, and the served default still reaches it on the first read.
 */
class AppSettingsNextPickPreferences(
    private val queries: AppSettingsQueries,
    private val dispatchers: AppDispatchers,
    private val remoteConfig: RemoteConfig,
) : NextPickPreferences {

    override val sessionLength: Flow<SessionLength> = settings()
        .map { row -> SessionLength.fromStored(row?.nextPickSessionLength) }

    override val resultMode: Flow<ResultMode> = settings()
        .map { row -> resolveResultMode(row?.nextPickResultMode) }

    override suspend fun setSessionLength(length: SessionLength) = withContext(dispatchers.io) {
        ensureRow()
        queries.updateNextPickSessionLength(length.name)
        Unit
    }

    override suspend fun setResultMode(mode: ResultMode) = withContext(dispatchers.io) {
        ensureRow()
        queries.updateNextPickResultMode(mode.name)
        Unit
    }

    private fun settings(): Flow<AppSettingsRow?> = queries.selectSettings()
        .asFlow()
        .mapToOneOrNull(dispatchers.io)
        .onStart { ensureRow() }

    private suspend fun ensureRow() = withContext(dispatchers.io) { queries.ensureRow() }

    /**
     * The one resolver for the result mode. The served value is read from the stored copy ([RemoteConfig.awaitStored]),
     * never the network, and only when nothing was chosen, so a stored choice costs no read. Unknown served text reads
     * as the enum default, the same tolerance [ResultMode.fromStored] gives storage.
     *
     * Deck is offered (#554 landed), so a served Deck resolves to Deck like any other mode.
     */
    private suspend fun resolveResultMode(stored: String?): ResultMode {
        if (stored != null) return ResultMode.fromStored(stored)
        val served = remoteConfig.awaitStored().nextPickDefaultResultMode
        return ResultMode.fromStored(served.trim().uppercase())
    }
}
