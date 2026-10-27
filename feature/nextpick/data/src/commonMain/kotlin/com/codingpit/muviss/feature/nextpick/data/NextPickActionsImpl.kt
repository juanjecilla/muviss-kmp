package com.codingpit.muviss.feature.nextpick.data

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.network.MetadataProvider
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.nextpick.domain.NextPickActions
import com.codingpit.muviss.feature.triage.api.TriageApi
import com.codingpit.muviss.models.MediaSummary

/**
 * The three buttons on a Next Pick, through peers' `:api` only (ADR 0004). Add fetches the full details
 * because a collection row is written from [com.codingpit.muviss.models.MediaDetails]; a candidate only
 * carries a summary. Snooze uses the default one-week period.
 */
internal class DefaultNextPickActions(
    private val collection: () -> CollectionApi,
    private val triage: () -> TriageApi,
    private val metadata: MetadataProvider,
    private val clock: AppClock,
) : NextPickActions {

    override suspend fun add(summary: MediaSummary) {
        collection().add(metadata.details(summary.id))
    }

    override suspend fun notForMe(summary: MediaSummary) {
        triage().skip(summary)
    }

    override suspend fun snooze(summary: MediaSummary) {
        triage().snooze(summary, dueAtEpochDay = clock.nowEpochMs().floorDiv(MS_PER_DAY) + SNOOZE_DAYS)
    }

    private companion object {
        const val MS_PER_DAY = 86_400_000L
        const val SNOOZE_DAYS = 7L
    }
}
