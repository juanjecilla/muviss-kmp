package com.codingpit.muviss.feature.profile.domain

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.todayEpochDay
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.progress.api.ProgressApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Observes [ProfileStats], recomputed whenever the library or watch progress
 * changes. Joins collection's and progress's `:api` directly (ADR 0004 allows
 * cross-feature `:api` dependencies from any of a feature's own layers, not
 * just its data layer — `feature/progress/ui`'s `ProgressViewModel` already
 * depends on `collection:api` the same way) and hands both inputs to the pure
 * [ProfileStatsCalculator].
 */
class ObserveProfileStatsUseCase(
    private val collectionApi: CollectionApi,
    private val progressApi: ProgressApi,
    private val clock: AppClock,
) {
    operator fun invoke(): Flow<ProfileStats> = combine(
        collectionApi.observeSummaries(),
        progressApi.observeSeenActivityEpochDays(),
    ) { summaries, activityDays ->
        ProfileStatsCalculator.calculate(summaries, activityDays, clock.todayEpochDay())
    }
}
