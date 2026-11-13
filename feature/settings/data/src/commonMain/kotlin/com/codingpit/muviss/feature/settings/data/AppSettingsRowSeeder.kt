package com.codingpit.muviss.feature.settings.data

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.remoteconfig.RemoteConfig
import com.codingpit.muviss.core.database.AppSettingsQueries
import com.codingpit.muviss.feature.nextpick.api.ResultMode
import kotlinx.coroutines.withContext

/**
 * Creates the singleton `appSettings` row when it does not exist yet (EPIC 51, #556). Every settings wrapper calls this
 * before a read or write, in place of a bare `ensureRow()`, so the one place that knows the fresh-row seed is here.
 *
 * A fresh row starts its Next Pick result mode from Remote Config's `nextPickDefaultResultMode`. An existing row is
 * never touched: the `INSERT OR IGNORE` keeps the stored choice, and the check below means the common path reads no
 * Remote Config at all. Read through [RemoteConfig.awaitStored] (the stored copy, never the network).
 */
internal class AppSettingsRowSeeder(
    private val queries: AppSettingsQueries,
    private val remoteConfig: RemoteConfig,
    private val dispatchers: AppDispatchers,
) {
    suspend fun ensureRow() = withContext(dispatchers.io) {
        if (queries.selectSettings().awaitAsOneOrNull() != null) return@withContext
        val served = remoteConfig.awaitStored().nextPickDefaultResultMode
        queries.ensureRowWithNextPickMode(seededResultMode(served).name)
    }
}

/**
 * The result mode a fresh row starts on, given what Remote Config served. Unknown text reads as the enum default, the
 * same tolerance [ResultMode.fromStored] gives storage. Deck is refused here: Settings does not offer it until its
 * handoff exists (#554), so a served Deck would seed a choice the person cannot see or change.
 */
internal fun seededResultMode(served: String): ResultMode = ResultMode.fromStored(served.trim().uppercase())
    .takeUnless { it == ResultMode.DECK }
    ?: ResultMode.DEFAULT
