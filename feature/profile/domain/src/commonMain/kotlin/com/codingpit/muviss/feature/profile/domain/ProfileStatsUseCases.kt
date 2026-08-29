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
        progressApi.observeRewatchCounts(RewatchWindow.ALL_TIME.sinceEpochMs(clock.nowEpochMs())),
    ) { summaries, activityDays, rewatchCounts ->
        ProfileStatsCalculator.calculate(summaries, activityDays, clock.todayEpochDay(), rewatchCounts)
    }
}

/** Everything the rewatch screen renders under one [RewatchWindow]. */
data class RewatchStats(
    val window: RewatchWindow = RewatchWindow.ALL_TIME,
    val ranking: RewatchRanking = RewatchRanking(),
    val monthly: List<MonthlyRewatches> = emptyList(),
)

/**
 * Observes the full rewatch screen: the ranking under the chosen [window],
 * and the monthly trend, which deliberately does *not* follow it.
 *
 * The trend is always the trailing twelve months — a rolling window is always
 * full and comparable month to month, where a calendar year renders a stub
 * every January and an all-time chart grows a bar per year. The two therefore
 * disagree on purpose, and the chart carries its own heading saying so
 * (ADR 0012). The trend also counts rewatches of titles no longer saved,
 * which the ranking drops, so the chart's total can exceed the sum of the
 * lists below it.
 */
class ObserveRewatchStatsUseCase(
    private val collectionApi: CollectionApi,
    private val progressApi: ProgressApi,
    private val clock: AppClock,
) {
    operator fun invoke(window: RewatchWindow): Flow<RewatchStats> {
        val today = clock.todayEpochDay()
        return combine(
            collectionApi.observeSummaries(),
            progressApi.observeRewatchCounts(window.sinceEpochMs(clock.nowEpochMs())),
            progressApi.observeRewatchTimestamps(MonthlyRewatchCalculator.sinceEpochMs(today)),
        ) { summaries, counts, timestamps ->
            RewatchStats(
                window = window,
                ranking = RewatchRankingCalculator.calculate(counts, summaries),
                monthly = MonthlyRewatchCalculator.calculate(timestamps, today),
            )
        }
    }
}
