package com.codingpit.muviss.feature.collection.domain

import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.ProductionStatus
import com.codingpit.muviss.models.WatchStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class CollectionEntryTest {

    private fun entry(
        airedEpisodes: Int = 10,
        totalEpisodes: Int = 10,
        productionStatus: ProductionStatus = ProductionStatus.ENDED,
        favorite: Boolean = false,
        seenEpisodes: Int = 0,
    ) = CollectionEntry(
        mediaId = MediaId.tmdbTv("1399"),
        title = "Title",
        posterUrl = null,
        releaseYear = 2011,
        productionStatus = productionStatus,
        totalEpisodes = totalEpisodes,
        airedEpisodes = airedEpisodes,
        favorite = favorite,
        addedAtEpochMs = 0L,
        seenEpisodes = seenEpisodes,
    )

    @Test
    fun mediaType_reads_off_mediaId() {
        assertEquals(MediaType.TV, entry().mediaType)
    }

    @Test
    fun no_progress_yet_always_derives_not_started() {
        // ADR 0005: without a progress feature, seenEpisodes is always 0, so
        // status must be NOT_STARTED regardless of aired count/production status.
        assertEquals(WatchStatus.NOT_STARTED, entry(productionStatus = ProductionStatus.ENDED).status)
        assertEquals(WatchStatus.NOT_STARTED, entry(productionStatus = ProductionStatus.RETURNING).status)
    }

    @Test
    fun favorite_is_independent_of_status() {
        val notFavorite = entry(favorite = false)
        val favorite = entry(favorite = true)
        assertEquals(notFavorite.status, favorite.status)
    }

    @Test
    fun status_reacts_to_seenEpisodes_once_progress_exists() {
        assertEquals(WatchStatus.WATCHING, entry(seenEpisodes = 3, airedEpisodes = 10, totalEpisodes = 10).status)
        assertEquals(
            WatchStatus.FINISHED,
            entry(seenEpisodes = 10, airedEpisodes = 10, totalEpisodes = 10, productionStatus = ProductionStatus.ENDED).status,
        )
    }
}
