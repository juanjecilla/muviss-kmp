@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.codingpit.muviss.core.sync

import kotlinx.serialization.descriptors.elementNames
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A tripwire, not a description.
 *
 * [SyncChangeSet] enumerates the tables [SyncEngine] replicates. `episode` is
 * deliberately *not* among them: it is not the user's data at all — it is
 * TMDB's catalog, cached locally (ADR 0015) — and pushing a provider's
 * episode list to a personal sync backend would waste bandwidth replicating
 * something every device can refetch for itself.
 *
 * `episode` is the easy mistake: it looks like every other table in
 * `core/database`, and adding a field here is a one-line change. Whoever
 * makes that change should have to come here and mean it.
 *
 * This test has already earned its place once. `episodePlays` joined the set
 * when rewatch history started syncing on a derived key (ADR 0013), landing
 * from a branch written in parallel with this one — and the assertion below
 * failed on CI rather than letting the two changes pass each other in the
 * dark.
 */
class SyncChangeSetShapeTest {

    @Test
    fun `the change set covers exactly the user-owned tables`() {
        assertEquals(
            listOf("collectionEntries", "episodeProgress", "mediaLists", "listEntries", "triageDecisions", "triageSnoozes", "episodePlays"),
            SyncChangeSet.serializer().descriptor.elementNames.toList(),
            "adding a table to sync is a deliberate act — see ADR 0009's six touchpoints, and ADR 0015 for why the episode catalog is not one of them",
        )
    }
}
