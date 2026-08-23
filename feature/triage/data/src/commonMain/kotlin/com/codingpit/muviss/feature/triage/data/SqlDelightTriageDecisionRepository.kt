package com.codingpit.muviss.feature.triage.data

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.database.TriageDecisionQueries
import com.codingpit.muviss.feature.triage.api.TriageVerdict
import com.codingpit.muviss.feature.triage.domain.TriageDecision
import com.codingpit.muviss.feature.triage.domain.TriageDecisionRepository
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import com.codingpit.muviss.core.database.TriageDecision as TriageDecisionRow

/**
 * SQLDelight-backed decision log over `TriageDecision.sq`.
 *
 * Every write stamps `updatedAtEpochMs` and sets `isDirty`, the change-log
 * discipline ADR 0002 put on every user-owned table; `restore` soft-deletes
 * rather than deleting so the tombstone reaches other devices.
 *
 * A row whose stored `verdict` string is unrecognised — written by a newer
 * build, or pulled from one — is dropped from reads rather than crashing.
 * It still counts as decided (`observeDecidedIds` reads ids straight from
 * SQL), which is the conservative choice: an unreadable decision should keep
 * a title out of the deck, not put it back in.
 */
class SqlDelightTriageDecisionRepository(
    private val queries: TriageDecisionQueries,
    private val dispatchers: AppDispatchers,
    private val clock: AppClock,
) : TriageDecisionRepository {

    override fun observeByVerdict(verdict: TriageVerdict): Flow<List<TriageDecision>> = queries.selectByVerdict(verdict.name)
        .asFlow()
        .mapToList(dispatchers.io)
        .map { rows -> rows.mapNotNull { it.toDomain() } }

    override fun observeDecidedIds(): Flow<Set<MediaId>> = queries.selectDecidedIds()
        .asFlow()
        .mapToList(dispatchers.io)
        .map { ids -> ids.mapNotNullTo(mutableSetOf()) { it.toMediaIdOrNull() } }

    override fun observeDecision(mediaId: MediaId): Flow<TriageDecision?> = queries.selectById(mediaId.toString())
        .asFlow()
        .mapToOneOrNull(dispatchers.io)
        .map { row -> row?.takeUnless { it.deleted }?.toDomain() }

    override suspend fun record(decision: TriageDecision) = withContext(dispatchers.io) {
        val now = clock.nowEpochMs()
        queries.upsert(
            mediaId = decision.mediaId.toString(),
            mediaType = decision.mediaId.type.wireName,
            verdict = decision.verdict.name,
            title = decision.title,
            posterUrl = decision.posterUrl,
            decidedAtEpochMs = decision.decidedAtEpochMs,
            resolved = decision.resolved,
            updatedAtEpochMs = now,
            isDirty = true,
            // Re-triaging a restored title revives its row rather than
            // leaving a tombstone that would hide the new decision.
            deleted = false,
        )
        Unit
    }

    override suspend fun markResolved(mediaId: MediaId, resolved: Boolean) = withContext(dispatchers.io) {
        queries.markResolved(resolved = resolved, now = clock.nowEpochMs(), mediaId = mediaId.toString())
        Unit
    }

    override suspend fun restore(mediaId: MediaId) = withContext(dispatchers.io) {
        queries.softDelete(now = clock.nowEpochMs(), mediaId = mediaId.toString())
        Unit
    }

    override suspend fun unresolved(): List<TriageDecision> = withContext(dispatchers.io) {
        queries.selectUnresolved().awaitAsList().mapNotNull { it.toDomain() }
    }

    private fun TriageDecisionRow.toDomain(): TriageDecision? {
        val id = mediaId.toMediaIdOrNull() ?: return null
        val parsed = TriageVerdict.fromStored(verdict) ?: return null
        return TriageDecision(
            mediaId = id,
            verdict = parsed,
            title = title,
            posterUrl = posterUrl,
            decidedAtEpochMs = decidedAtEpochMs,
            resolved = resolved,
        )
    }

    private fun String.toMediaIdOrNull(): MediaId? = runCatching { MediaId.parse(this) }.getOrNull()
}
