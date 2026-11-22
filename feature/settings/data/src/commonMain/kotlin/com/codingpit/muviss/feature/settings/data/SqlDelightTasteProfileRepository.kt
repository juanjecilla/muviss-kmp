package com.codingpit.muviss.feature.settings.data

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.database.TasteProfile
import com.codingpit.muviss.core.database.TasteProfileQueries
import com.codingpit.muviss.feature.settings.api.TasteItem
import com.codingpit.muviss.feature.settings.api.TasteKind
import com.codingpit.muviss.feature.settings.domain.TasteProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * `tasteProfile` (EPIC 51, #402): one row per liked item, soft-deleted on unlike.
 *
 * Writes only what changes. Liking an item that is already liked, or unliking one that is
 * not, touches nothing, so the stamp never moves for a no-op and sync does not push it. A
 * write carries the clock's time as its stamp and is dirty until a sync pushes it.
 */
class SqlDelightTasteProfileRepository(
    private val queries: TasteProfileQueries,
    private val dispatchers: AppDispatchers,
    private val clock: AppClock,
) : TasteProfileRepository {

    override fun observe(): Flow<List<TasteItem>> = queries.selectAll()
        .asFlow()
        .mapToList(dispatchers.io)
        .map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun setLiked(item: TasteItem, liked: Boolean) = withContext(dispatchers.io) {
        val existing = queries.selectById(item.id).awaitAsOneOrNull()
        val alreadyLiked = existing?.deleted == false
        if (liked == alreadyLiked) return@withContext
        if (liked) {
            queries.upsert(
                id = item.id,
                kind = item.kind.name,
                itemValue = item.value,
                label = item.label,
                updatedAtEpochMs = clock.nowEpochMs(),
                isDirty = true,
                deleted = false,
            )
        } else {
            queries.softDelete(now = clock.nowEpochMs(), id = item.id)
        }
    }

    /** Null for a kind this build does not know, so a row from a newer device is dropped, not crashed on. */
    private fun TasteProfile.toDomain(): TasteItem? {
        val kind = TasteKind.entries.firstOrNull { it.name == kind } ?: return null
        return TasteItem(kind = kind, value = itemValue, label = label)
    }
}
