package com.codingpit.muviss.feature.nextpick.data

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.flags.FeatureFlags
import com.codingpit.muviss.core.common.flags.SnoozePeriod
import com.codingpit.muviss.core.common.todayEpochDay
import com.codingpit.muviss.core.network.MetadataProvider
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.nextpick.domain.NextPickActions
import com.codingpit.muviss.feature.triage.api.TriageApi
import com.codingpit.muviss.models.MediaSummary
import kotlinx.coroutines.flow.first

/**
 * The three buttons on a Next Pick, through peers' `:api` only (ADR 0004). Add fetches the full details
 * because a collection row is written from [com.codingpit.muviss.models.MediaDetails]; a candidate only
 * carries a summary. Snooze follows the person's `triageSnoozePeriod`, the same setting the deck reads (#551).
 */
internal class DefaultNextPickActions(
    private val collection: () -> CollectionApi,
    private val triage: () -> TriageApi,
    private val metadata: MetadataProvider,
    private val clock: AppClock,
    private val flags: FeatureFlags,
) : NextPickActions {

    override suspend fun add(summary: MediaSummary) {
        collection().add(metadata.details(summary.id))
    }

    override suspend fun notForMe(summary: MediaSummary) {
        triage().skip(summary)
    }

    override suspend fun snooze(summary: MediaSummary) {
        val period = flags.triageSnoozePeriod.first()
        triage().snooze(summary, dueAtEpochDay = snoozeDueEpochDay(period, clock.todayEpochDay()))
    }
}

/**
 * The epoch day a Next Pick's Snooze comes back on. [SnoozePeriod.ASK_EACH_TIME] has no duration and a
 * Pick Session has no date picker, so it falls back to [SnoozePeriod.DEFAULT] rather than asking.
 */
internal fun snoozeDueEpochDay(period: SnoozePeriod, todayEpochDay: Long): Long {
    val days = period.days ?: checkNotNull(SnoozePeriod.DEFAULT.days)
    return todayEpochDay + days
}
