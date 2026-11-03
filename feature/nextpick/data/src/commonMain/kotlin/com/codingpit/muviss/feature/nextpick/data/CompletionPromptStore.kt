package com.codingpit.muviss.feature.nextpick.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.database.AppSettingsQueries
import com.codingpit.muviss.feature.nextpick.domain.CompletionPromptStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

/**
 * The completion sheet's once-a-day memory, on the singleton `appSettings` row (per device, not synced:
 * the same reasoning as triage's onboarding flags). `ensureRow` runs before each access, as the other
 * `appSettings` readers do, so a fresh install reads "never shown" rather than failing.
 *
 * [enabled] is the #403 seam: it is always on until the Settings switch lands.
 */
internal class AppSettingsCompletionPromptStore(
    private val queries: AppSettingsQueries,
    private val dispatchers: AppDispatchers,
) : CompletionPromptStore {

    override val enabled: Flow<Boolean> = flowOf(true)

    override suspend fun lastShownAtEpochMs(): Long? = withContext(dispatchers.io) {
        ensureRow()
        queries.selectSettings().asFlow().mapToOneOrNull(dispatchers.io).first()?.completionPromptShownAtEpochMs
    }

    override suspend fun markShown(atEpochMs: Long) = withContext(dispatchers.io) {
        ensureRow()
        queries.updateCompletionPromptShownAt(atEpochMs)
        Unit
    }

    private suspend fun ensureRow() = queries.ensureRow()
}
