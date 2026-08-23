package com.codingpit.muviss.feature.search.ui

import com.codingpit.muviss.feature.triage.api.SkippedTitle
import com.codingpit.muviss.feature.triage.api.TriageApi
import com.codingpit.muviss.feature.triage.api.TriageDecisionSummary
import com.codingpit.muviss.feature.triage.api.TriageVerdict
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Stand-in for the triage feature. Search reads it for two things only:
 * excluding skipped titles from "For you", and the Detail screen's undo line.
 */
internal class FakeTriageApi(initial: Map<MediaId, TriageVerdict> = emptyMap()) : TriageApi {
    val decisions = MutableStateFlow(initial)
    val restored = mutableListOf<MediaId>()

    override fun observeDecidedIds(): Flow<Set<MediaId>> = decisions.map { it.keys }

    override fun observeSkipped(): Flow<List<SkippedTitle>> = decisions.map { all ->
        all.filterValues { it == TriageVerdict.SKIP }.keys.map { SkippedTitle(it, it.external, null, 0L) }
    }

    override fun observeDecision(mediaId: MediaId): Flow<TriageDecisionSummary?> = decisions.map { all ->
        all[mediaId]?.let { TriageDecisionSummary(mediaId, it, decidedAtEpochMs = 0L) }
    }

    override suspend fun restore(mediaId: MediaId) {
        restored += mediaId
        decisions.value = decisions.value - mediaId
    }
}
