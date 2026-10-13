package com.codingpit.muviss.feature.onboarding.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.database.AppSettingsQueries
import com.codingpit.muviss.feature.onboarding.domain.OnboardingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import com.codingpit.muviss.core.database.AppSettings as AppSettingsRow

/**
 * Onboarding's two flags, as columns on the singleton `appSettings` row (EPIC 47):
 * per-device and never synced, like every preference beside them.
 *
 * `ensureRow` runs before every read and write, as the other `appSettings` readers do. It is
 * also what makes a fresh install start at `onboardingCompleted = 0`: only a row it creates
 * does; the migration marked every row that already existed as completed.
 *
 * A missing row reads as *completed*, the opposite of a fresh install's default: the one way
 * to see no row is a read that loses a race with `ensureRow`, and that must not flash the
 * introduction at someone who has used the app for months.
 */
internal class SqlDelightOnboardingRepository(
    private val queries: AppSettingsQueries,
    private val dispatchers: AppDispatchers,
) : OnboardingRepository {

    override fun observeCompleted(): Flow<Boolean> = settings().map { it?.onboardingCompleted ?: true }.distinctUntilChanged()

    override suspend fun markCompleted() = withContext(dispatchers.io) {
        queries.ensureRow()
        queries.updateOnboardingCompleted(true)
        Unit
    }

    override fun observeNudgeDismissed(): Flow<Boolean> = settings().map { it?.notificationNudgeDismissed ?: true }.distinctUntilChanged()

    override suspend fun dismissNudge() = withContext(dispatchers.io) {
        queries.ensureRow()
        queries.updateNotificationNudgeDismissed(true)
        Unit
    }

    private fun settings(): Flow<AppSettingsRow?> = queries.selectSettings()
        .asFlow()
        .mapToOneOrNull(dispatchers.io)
        .onStart { withContext(dispatchers.io) { queries.ensureRow() } }
}
