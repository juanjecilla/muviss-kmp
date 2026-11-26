package com.codingpit.muviss.appfunctions

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.network.MetadataProviderRegistry
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.progress.api.NextEpisodes
import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.Season
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * `getTitleProgress` against the two collaborators it reads. [unusedApi] stands in for every member the test does
 * not exercise: calling one throws, so an accidental dependency fails loudly instead of answering wrongly.
 */
class AppFunctionTitleProgressTest {

    private val show = MediaId.tmdbTv("1399")

    // Local epoch day 1 (1970) has aired and 2,000,000 (about 5400 AD) has not, whatever the host's date is.
    private val aired = 1L
    private val unaired = 2_000_000L

    private val clock = object : AppClock {
        // Local day 10: the aired episodes below (day 1) are in the past, the unaired one (day 2,000,000) is not.
        override fun nowEpochMs(): Long = 10L * 86_400_000L
    }

    private fun episode(season: Int, number: Int, airDay: Long) = Episode(
        id = EpisodeId(show, season, number),
        seasonNumber = season,
        episodeNumber = number,
        name = "S${season}E$number",
        airDateEpochDay = airDay,
    )

    private fun tick(season: Int, number: Int) = EpisodeId(show, season, number)

    private fun library(seasons: List<Season>?, seen: Set<EpisodeId>): AppFunctionLibrary = AppFunctionLibrary(
        progress = FakeProgress(seasons, seen),
        collection = FakeCollection(listOf(savedShow())),
        providers = MetadataProviderRegistry(emptyList()),
        clock = clock,
    )

    private fun savedShow() = CollectionSummary(
        mediaId = show,
        title = "Game of Thrones",
        posterUrl = null,
        status = WatchStatus.WATCHING,
    )

    @Test
    fun seen_count_counts_the_ticks_when_the_catalog_is_not_loaded() = runTest {
        // The catalog is not stored, so nothing is known to have aired. The ticks the user made still count.
        val progress = library(seasons = null, seen = setOf(tick(1, 1), tick(1, 2)))
            .titleProgress(show)

        assertFalse(progress.catalogLoaded)
        assertEquals(2, progress.seenCount)
        assertEquals(0, progress.airedCount)
        assertNull(progress.nextEpisodeId)
    }

    @Test
    fun seen_count_counts_only_aired_episodes_when_the_catalog_is_loaded() = runTest {
        val seasons = listOf(
            Season(number = 1, name = "Season 1", episodes = listOf(episode(1, 1, aired), episode(1, 2, aired), episode(1, 3, unaired))),
        )
        val progress = library(seasons, seen = setOf(tick(1, 1), tick(1, 2))).titleProgress(show)

        assertEquals(2, progress.seenCount)
        assertEquals(2, progress.airedCount)
        assertNull(progress.nextEpisodeId)
    }
}

/** A [ProgressApi] that answers the three members `titleProgress` reads and throws for the rest. */
private class FakeProgress(
    private val seasons: List<Season>?,
    private val seen: Set<EpisodeId>,
) : ProgressApi by unusedApi(ProgressApi::class.java) {
    override fun observeSeenEpisodes(mediaId: MediaId) = flowOf(seen)
    override suspend fun storedSeasons(mediaId: MediaId): List<Season>? = seasons
    override fun nextEpisodes(seasons: List<Season>, seen: Set<EpisodeId>, todayEpochDay: Long): NextEpisodes = NextEpisodes.of(seasons, seen, todayEpochDay)
}

/** A [CollectionApi] that answers the one member the progress functions read and throws for the rest. */
private class FakeCollection(
    private val summaries: List<CollectionSummary>,
) : CollectionApi by unusedApi(CollectionApi::class.java) {
    override fun observeSummaries() = flowOf(summaries)
}

private fun <T : Any> unusedApi(type: Class<T>): T = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
    throw UnsupportedOperationException("${type.simpleName}.${method.name} is not used by this test")
} as T
