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
 * first refresh anyway (ADR 0015).
 *
 * The Discover caches (`discoverCache`, #724, and `forYouCache`, #785) are in
 * the same family and are left out for the same reason. The exact field list
 * below is what keeps them out, and a restore never writes them.
 */
class ExportShapeTest {

    @Test
    fun `the export covers exactly the user's own data`() {
        assertEquals(
            listOf(
                "formatVersion", "appVersion", "exportedAtEpochMs", "collection", "progress", "triage", "plays",
                "snoozes", "lists", "listEntries", "profile", "settings", "tasteProfile", "mutedGenreChoice", "mutedGenres",
            ),
            MuvissDataExport.serializer().descriptor.elementNames.toList(),
            "the episode catalog is TMDB's data cached locally, not the user's — see ADR 0015",
        )
    }

    /**
     * #902 and #790 added two fields to a triage decision. Both default, so the checked-in v1 fixture still
     * parses; this pins their names, which a backup written by a later build must carry.
     */
    @Test
    fun `a triage decision exports the entry stamp and the ticks its verdict wrote`() {
        assertEquals(
            listOf(
                "mediaId", "mediaType", "verdict", "title", "posterUrl", "decidedAtEpochMs", "updatedAtEpochMs",
                "resolved", "createdEntry", "entryAddedAtEpochMs", "tickedEpisodeIds",
            ),
            TriageDecisionExport.serializer().descriptor.elementNames.toList(),
        )
    }

    @Test
    fun `the display preferences group is exported with its one stamp`() {
        assertEquals(
            listOf(
                "theme", "language", "region", "animationsEnabled", "triageDeckAnimations", "tickAnimations",
                "triageControlScheme", "triageSnoozePeriod", "triageSnoozePlacement", "mutedGenres", "mutedGenresInSearch",
                "displayPreferencesUpdatedAtEpochMs",
            ),
            SettingsExport.serializer().descriptor.elementNames.toList(),
        )
    }
}
