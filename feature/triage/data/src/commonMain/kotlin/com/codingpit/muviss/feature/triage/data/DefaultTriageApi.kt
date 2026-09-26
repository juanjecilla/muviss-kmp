package com.codingpit.muviss.feature.triage.data

import com.codingpit.muviss.feature.triage.api.SkippedTitle
import com.codingpit.muviss.feature.triage.api.SnoozedTitle
import com.codingpit.muviss.feature.triage.api.TriageApi
import com.codingpit.muviss.feature.triage.api.TriageDecisionSummary
import com.codingpit.muviss.feature.triage.domain.ObserveDecidedIdsUseCase
import com.codingpit.muviss.feature.triage.domain.ObserveDecisionUseCase
import com.codingpit.muviss.feature.triage.domain.ObserveSkippedUseCase
import com.codingpit.muviss.feature.triage.domain.RestoreDecisionUseCase
import com.codingpit.muviss.feature.triage.domain.SnoozeQueries
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Bridges triage's use cases to its public [TriageApi]. */
internal class DefaultTriageApi(
    private val observeDecidedIds: ObserveDecidedIdsUseCase,
    private val observeSkipped: ObserveSkippedUseCase,
    private val observeDecision: ObserveDecisionUseCase,
    private val restoreDecision: RestoreDecisionUseCase,
    private val snoozes: SnoozeQueries,
) : TriageApi {

    override fun observeDecidedIds(): Flow<Set<MediaId>> = observeDecidedIds.invoke()

    override fun observeSkipped(): Flow<List<SkippedTitle>> = observeSkipped.invoke()

    override fun observeDecision(mediaId: MediaId): Flow<TriageDecisionSummary?> = observeDecision.invoke(mediaId)
        .map { decision ->
            decision?.let { TriageDecisionSummary(it.mediaId, it.verdict, it.decidedAtEpochMs, it.resolved) }
        }

    override suspend fun restore(mediaId: MediaId) = restoreDecision.invoke(mediaId)

    override fun observeSnoozedIds(): Flow<Set<MediaId>> = snoozes.observeSnoozedIds.invoke()

    override fun observeSnoozed(): Flow<List<SnoozedTitle>> = snoozes.observeSnoozed.invoke()

    override fun observeSnooze(mediaId: MediaId): Flow<SnoozedTitle?> = snoozes.observeSnooze.invoke(mediaId)
        .map { snooze ->
            snooze?.let { SnoozedTitle(it.mediaId, it.title, it.posterUrl, it.snoozedAtEpochMs, it.dueAtEpochDay) }
        }

    override suspend fun unsnooze(mediaId: MediaId) = snoozes.unsnooze.invoke(mediaId)
}
