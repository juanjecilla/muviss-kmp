@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.codingpit.muviss.core.sync

import kotlinx.serialization.descriptors.elementNames
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A tripwire, not a description.
 *
 * [SyncChangeSet] enumerates the tables [SyncEngine] replicates, and two
 * local tables are deliberately *not* among them for two different reasons:
 * `episodePlay` because append-only rows need a merge rule ADR 0009's
 * last-write-wins does not provide, and `episode` because it is not the
 * user's data at all — it is TMDB's catalog, cached locally (ADR 0013), and
 * pushing a provider's episode list to a personal sync backend would waste
 * bandwidth replicating something every device can refetch for itself.
 *
 * `episode` is also the easier mistake: it looks like every other table in
 * `core/database`, and adding a field here is a one-line change. Whoever
 * makes that change should have to come here and mean it.
 */
class SyncChangeSetShapeTest {

    @Test
    fun `the change set covers exactly the user-owned tables`() {
        assertEquals(
            listOf("collectionEntries", "episodeProgress", "mediaLists", "listEntries", "triageDecisions"),
            SyncChangeSet.serializer().descriptor.elementNames.toList(),
            "adding a table to sync is a deliberate act — see ADR 0009's six touchpoints, and ADR 0013 for why the episode catalog is not one of them",
        )
    }
}
