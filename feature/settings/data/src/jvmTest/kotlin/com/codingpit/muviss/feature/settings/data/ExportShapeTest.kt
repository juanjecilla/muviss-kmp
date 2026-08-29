@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.codingpit.muviss.feature.settings.data

import kotlinx.serialization.descriptors.elementNames
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The companion tripwire to `:core:sync`'s `SyncChangeSetShapeTest`.
 *
 * The export is a person's own backup, which is why it carries rewatch
 * history that sync does not. The episode catalog is still excluded, and for
 * a different reason than sync's: it is not theirs to back up. Every row in
 * it can be refetched from TMDB, and including it would inflate an export by
 * an order of magnitude with data the importing install would overwrite on
 * first refresh anyway (ADR 0013).
 */
class ExportShapeTest {

    @Test
    fun `the export covers exactly the user's own data`() {
        assertEquals(
            listOf("exportedAtEpochMs", "collection", "progress", "triage", "plays"),
            MuvissDataExport.serializer().descriptor.elementNames.toList(),
            "the episode catalog is TMDB's data cached locally, not the user's — see ADR 0013",
        )
    }
}
