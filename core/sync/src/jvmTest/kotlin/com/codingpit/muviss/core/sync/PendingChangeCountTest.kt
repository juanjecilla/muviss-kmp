package com.codingpit.muviss.core.sync

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Profile's "N changes waiting" is `countDirtyRows`, a hand-written sum over the synced tables.
 * A table added to [SyncTable] is pushed at once but was left out of that sum twice
 * (`triageSnooze`, then `tasteProfile`), so a pending edit read as nothing waiting.
 */
class PendingChangeCountTest {

    @Test
    fun every_synced_table_counts_towards_the_pending_changes() {
        val query = File("../database/src/commonMain/sqldelight/com/codingpit/muviss/core/database/SyncState.sq")
            .readText()
            .substringAfter("countDirtyRows:")
            .substringBefore(";")
        SyncTable.entries.forEach { table ->
            // The Display Preferences group is one row of `appSettings`, dirty by its own flag (ADR 0036).
            val clause = when (table) {
                SyncTable.DISPLAY_PREFERENCES -> "FROM appSettings WHERE id = 0 AND displayPreferencesDirty = 1"
                else -> "FROM ${table.cursorKey} WHERE isDirty = 1"
            }
            assertTrue(clause in query, "countDirtyRows leaves out ${table.cursorKey}")
        }
    }
}
