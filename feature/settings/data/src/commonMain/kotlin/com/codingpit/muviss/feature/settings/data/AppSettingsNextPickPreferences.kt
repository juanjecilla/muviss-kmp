package com.codingpit.muviss.feature.settings.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.codingpit.muviss.core.common.AppDispatchers
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
 */
class AppSettingsNextPickPreferences(
    private val queries: AppSettingsQueries,
    private val dispatchers: AppDispatchers,
) : NextPickPreferences {

    override val sessionLength: Flow<SessionLength> = settings()
        .map { row -> SessionLength.fromStored(row?.nextPickSessionLength) }

    override val resultMode: Flow<ResultMode> = settings()
        .map { row -> ResultMode.fromStored(row?.nextPickResultMode) }

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
}
