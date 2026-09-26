package com.codingpit.muviss.feature.triage.data

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.database.TriageSnoozeQueries
import com.codingpit.muviss.feature.triage.domain.TriageSnooze
import com.codingpit.muviss.feature.triage.domain.TriageSnoozeRepository
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import com.codingpit.muviss.core.database.TriageSnooze as TriageSnoozeRow

/**
 * SQLDelight-backed snooze log over `TriageSnooze.sq` (ADR 0023).
 *
 * Mirrors [SqlDelightTriageDecisionRepository]'s discipline: every write
 * stamps `updatedAtEpochMs` and sets `isDirty` (ADR 0002), and [unsnooze]
 * soft-deletes so the tombstone reaches other devices instead of their copy
 * being pushed back — the `episodePlay` lesson from ADR 0013. Every read
 * below therefore goes through a query that filters `deleted = 0`; the one
 * exception, `selectById`, is not used here.
 *
 * A row whose stored `mediaId` will not parse is dropped from reads rather
 * than crashing. Unlike a decision, dropping a Snooze is the *permissive*
 * outcome — the title simply becomes deck-eligible again — which is the right
 * way round: an unreadable postponement should not hide a title forever.
 */
class SqlDelightTriageSnoozeRepository(
    private val queries: TriageSnoozeQueries,
    private val dispatchers: AppDispatchers,
    private val clock: AppClock,
) : TriageSnoozeRepository {

    override fun observeAll(): Flow<List<TriageSnooze>> = queries.selectAll()
        .asFlow()
        .mapToList(dispatchers.io)
        .map { rows -> rows.mapNotNull { it.toDomain() } }

    override fun observeSnoozedIds(): Flow<Set<MediaId>> = queries.selectSnoozedIds()
        .asFlow()
        .mapToList(dispatchers.io)
        .map { ids -> ids.mapNotNullTo(mutableSetOf()) { it.toMediaIdOrNull() } }

    override fun observeSnooze(mediaId: MediaId): Flow<TriageSnooze?> = queries.selectById(mediaId.toString())
        .asFlow()
        .mapToOneOrNull(dispatchers.io)
        .map { row -> row?.takeUnless { it.deleted }?.toDomain() }

    override suspend fun snooze(snooze: TriageSnooze) = withContext(dispatchers.io) {
        queries.upsert(
            mediaId = snooze.mediaId.toString(),
            mediaType = snooze.mediaId.type.wireName,
            title = snooze.title,
            year = snooze.year?.toLong(),
            posterUrl = snooze.posterUrl,
            overview = snooze.overview,
            snoozedAtEpochMs = snooze.snoozedAtEpochMs,
            dueAtEpochDay = snooze.dueAtEpochDay,
            updatedAtEpochMs = clock.nowEpochMs(),
            isDirty = true,
            // Re-snoozing an unsnoozed title revives its row rather than
            // leaving a tombstone that would hide the new due date.
            deleted = false,
        )
        Unit
    }

    override suspend fun unsnooze(mediaId: MediaId) = withContext(dispatchers.io) {
        queries.softDelete(now = clock.nowEpochMs(), mediaId = mediaId.toString())
        Unit
    }

    override suspend fun due(today: Long): List<TriageSnooze> = withContext(dispatchers.io) {
        queries.selectDue(today).awaitAsList().mapNotNull { it.toDomain() }
    }

    private fun TriageSnoozeRow.toDomain(): TriageSnooze? {
        val id = mediaId.toMediaIdOrNull() ?: return null
        return TriageSnooze(
            mediaId = id,
            title = title,
            year = year?.toInt(),
            posterUrl = posterUrl,
            overview = overview,
            snoozedAtEpochMs = snoozedAtEpochMs,
            dueAtEpochDay = dueAtEpochDay,
        )
    }

    private fun String.toMediaIdOrNull(): MediaId? = runCatching { MediaId.parse(this) }.getOrNull()
}
