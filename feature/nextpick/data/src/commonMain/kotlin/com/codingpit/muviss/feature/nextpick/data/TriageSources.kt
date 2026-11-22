package com.codingpit.muviss.feature.nextpick.data

import com.codingpit.muviss.feature.nextpick.domain.PickExclusions
import com.codingpit.muviss.feature.triage.api.TriageApi
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.flow.first

/**
 * Everything Triage has ruled on, or is holding back for later: Skips, pending Snoozes. A pick never
 * repeats one of these (ADR 0032), which is why Not for me and Snooze can be the same writes Triage uses.
 */
internal class TriageExclusions(private val triage: () -> TriageApi) : PickExclusions {
    override suspend fun excluded(): Set<MediaId> {
        val api = triage()
        return api.observeDecidedIds().first() + api.observeSnoozedIds().first()
    }
}
