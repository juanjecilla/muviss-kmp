@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.collection.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.database.CollectionEntryQueries
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.ProductionStatus
import com.codingpit.muviss.models.Season
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class ImmediateDispatchers(d: CoroutineDispatcher) : AppDispatchers {
    override val default = d
    override val io = d
}

private class FakeClock(private var millis: Long) : AppClock {
    override fun nowEpochMs(): Long = millis
    fun advanceTo(newMillis: Long) {
        millis = newMillis
    }
}

/** Test double for [ProgressApi]: seen-episode sets are controlled per media id via [setSeen]. */
private class FakeProgressApi : ProgressApi {
    private val seenByMedia = mutableMapOf<MediaId, MutableStateFlow<Set<EpisodeId>>>()

    private fun flowFor(mediaId: MediaId) = seenByMedia.getOrPut(mediaId) { MutableStateFlow(emptySet()) }

    fun setSeen(mediaId: MediaId, seen: Set<EpisodeId>) {
        flowFor(mediaId).value = seen
    }

    override fun observeSeenEpisodes(mediaId: MediaId): Flow<Set<EpisodeId>> = flowFor(mediaId)
    override fun observeSeenActivityEpochDays(): Flow<Set<Long>> = error("not used")
    override suspend fun setEpisodeSeen(episodeId: EpisodeId, seen: Boolean) = error("not used")
    override suspend fun markSeasonSeen(season: Season) = error("not used")
    override suspend fun markPreviousSeen(seasons: List<Season>, target: EpisodeId) = error("not used")
    override suspend fun setMovieWatched(mediaId: MediaId, watched: Boolean) = error("not used")
}

class SqlDelightCollectionRepositoryTest {

    private lateinit var queries: CollectionEntryQueries
    private lateinit var clock: FakeClock
    private lateinit var progressApi: FakeProgressApi
    private lateinit var repository: SqlDelightCollectionRepository

    @BeforeTest
    fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MuvissDatabase.Schema.create(driver)
        queries = MuvissDatabase(driver).collectionEntryQueries
        clock = FakeClock(1_000L)
        progressApi = FakeProgressApi()
        repository = SqlDelightCollectionRepository(queries, ImmediateDispatchers(UnconfinedTestDispatcher()), clock, progressApi)
    }

    private fun details(
        id: MediaId = MediaId.tmdbMovie("603"),
        title: String = "The Matrix",
        productionStatus: ProductionStatus = ProductionStatus.RELEASED,
        genres: List<String> = emptyList(),
        runtimeMinutes: Int? = null,
    ) = MediaDetails(MediaSummary(id, title, year = 1999), productionStatus = productionStatus, genres = genres, runtimeMinutes = runtimeMinutes)

    @Test
    fun upsertSnapshot_adds_a_new_entry() = runTest {
        repository.upsertSnapshot(details())

        repository.observeAll().test {
            val entry = awaitItem().single()
            assertEquals(MediaId.tmdbMovie("603"), entry.mediaId)
            assertEquals("The Matrix", entry.title)
            assertEquals(false, entry.favorite)
            assertEquals(1_000L, entry.addedAtEpochMs)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun upsertSnapshot_refresh_preserves_favorite_and_original_addedAt() = runTest {
        repository.upsertSnapshot(details())
        repository.setFavorite(MediaId.tmdbMovie("603"), favorite = true)

        clock.advanceTo(5_000L)
        repository.upsertSnapshot(details(title = "The Matrix Reloaded"))

        repository.observeAll().test {
            val entry = awaitItem().single()
            assertEquals("The Matrix Reloaded", entry.title)
            assertTrue(entry.favorite)
            assertEquals(1_000L, entry.addedAtEpochMs)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun remove_soft_deletes_and_hides_from_observeAll() = runTest {
        repository.upsertSnapshot(details())
        repository.remove(MediaId.tmdbMovie("603"))

        repository.observeAll().test {
            assertTrue(awaitItem().isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun remove_then_readd_undeletes_and_keeps_original_addedAt() = runTest {
        repository.upsertSnapshot(details())
        repository.remove(MediaId.tmdbMovie("603"))

        clock.advanceTo(9_000L)
        repository.upsertSnapshot(details())

        repository.observeAll().test {
            val entry = awaitItem().single()
            assertEquals(1_000L, entry.addedAtEpochMs)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun upsertSnapshot_stores_genres_and_movie_runtime() = runTest {
        repository.upsertSnapshot(details(genres = listOf("Action", "Sci-Fi"), runtimeMinutes = 136))

        repository.observeAll().test {
            val entry = awaitItem().single()
            assertEquals(listOf("Action", "Sci-Fi"), entry.genres)
            assertEquals(136, entry.runtimeMinutes)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun upsertSnapshot_with_no_genres_or_runtime_round_trips_to_empty_and_null() = runTest {
        repository.upsertSnapshot(details())

        repository.observeAll().test {
            val entry = awaitItem().single()
            assertEquals(emptyList(), entry.genres)
            assertEquals(null, entry.runtimeMinutes)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun upsertSnapshot_stores_a_tv_show_average_episode_runtime() = runTest {
        val show = MediaId.tmdbTv("1399")
        val episodes = listOf(
            Episode(EpisodeId(show, 1, 1), 1, 1, "E1", runtimeMinutes = 40),
            Episode(EpisodeId(show, 1, 2), 1, 2, "E2", runtimeMinutes = 50),
            Episode(EpisodeId(show, 1, 3), 1, 3, "E3", runtimeMinutes = null), // unknown runtime, excluded from the average.
        )
        repository.upsertSnapshot(
            MediaDetails(
                MediaSummary(show, "Show"),
                productionStatus = ProductionStatus.RETURNING,
                seasons = listOf(Season(1, "S1", episodes)),
            ),
        )

        repository.observeAll().test {
            assertEquals(45, awaitItem().single().runtimeMinutes)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun setFavorite_toggles_independently_of_status() = runTest {
        repository.upsertSnapshot(details())
        repository.setFavorite(MediaId.tmdbMovie("603"), favorite = true)

        repository.observeEntry(MediaId.tmdbMovie("603")).test {
            val entry = awaitItem()
            assertTrue(entry!!.favorite)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun new_entries_default_to_not_muted() = runTest {
        repository.upsertSnapshot(details())

        repository.observeEntry(MediaId.tmdbMovie("603")).test {
            assertEquals(false, awaitItem()!!.notificationsMuted)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun setNotificationsMuted_round_trips_and_is_independent_of_favorite() = runTest {
        repository.upsertSnapshot(details())
        repository.setNotificationsMuted(MediaId.tmdbMovie("603"), muted = true)

        repository.observeEntry(MediaId.tmdbMovie("603")).test {
            val entry = awaitItem()!!
            assertTrue(entry.notificationsMuted)
            assertEquals(false, entry.favorite)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun setNotificationsMuted_survives_a_snapshot_refresh_like_favorite_does() = runTest {
        repository.upsertSnapshot(details())
        repository.setNotificationsMuted(MediaId.tmdbMovie("603"), muted = true)

        clock.advanceTo(5_000L)
        repository.upsertSnapshot(details(title = "The Matrix Reloaded"))

        repository.observeEntry(MediaId.tmdbMovie("603")).test {
            val entry = awaitItem()!!
            assertEquals("The Matrix Reloaded", entry.title)
            assertTrue(entry.notificationsMuted)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun new_entries_default_to_unrated_with_no_note() = runTest {
        repository.upsertSnapshot(details())

        repository.observeEntry(MediaId.tmdbMovie("603")).test {
            val entry = awaitItem()!!
            assertEquals(null, entry.rating)
            assertEquals(null, entry.note)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun setRating_round_trips_and_is_independent_of_favorite() = runTest {
        repository.upsertSnapshot(details())
        repository.setRating(MediaId.tmdbMovie("603"), 8)

        repository.observeEntry(MediaId.tmdbMovie("603")).test {
            val entry = awaitItem()!!
            assertEquals(8, entry.rating)
            assertEquals(false, entry.favorite)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun setRating_with_null_clears_it() = runTest {
        repository.upsertSnapshot(details())
        repository.setRating(MediaId.tmdbMovie("603"), 8)
        repository.setRating(MediaId.tmdbMovie("603"), null)

        repository.observeEntry(MediaId.tmdbMovie("603")).test {
            assertEquals(null, awaitItem()!!.rating)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun setRating_survives_a_snapshot_refresh_like_favorite_does() = runTest {
        repository.upsertSnapshot(details())
        repository.setRating(MediaId.tmdbMovie("603"), 9)

        clock.advanceTo(5_000L)
        repository.upsertSnapshot(details(title = "The Matrix Reloaded"))

        repository.observeEntry(MediaId.tmdbMovie("603")).test {
            val entry = awaitItem()!!
            assertEquals("The Matrix Reloaded", entry.title)
            assertEquals(9, entry.rating)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun setNote_round_trips_and_is_independent_of_rating() = runTest {
        repository.upsertSnapshot(details())
        repository.setNote(MediaId.tmdbMovie("603"), "Holds up on rewatch")

        repository.observeEntry(MediaId.tmdbMovie("603")).test {
            val entry = awaitItem()!!
            assertEquals("Holds up on rewatch", entry.note)
            assertEquals(null, entry.rating)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun setNote_with_null_clears_it() = runTest {
        repository.upsertSnapshot(details())
        repository.setNote(MediaId.tmdbMovie("603"), "Holds up on rewatch")
        repository.setNote(MediaId.tmdbMovie("603"), null)

        repository.observeEntry(MediaId.tmdbMovie("603")).test {
            assertEquals(null, awaitItem()!!.note)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun setNote_survives_a_snapshot_refresh_like_favorite_does() = runTest {
        repository.upsertSnapshot(details())
        repository.setNote(MediaId.tmdbMovie("603"), "Holds up on rewatch")

        clock.advanceTo(5_000L)
        repository.upsertSnapshot(details(title = "The Matrix Reloaded"))

        repository.observeEntry(MediaId.tmdbMovie("603")).test {
            val entry = awaitItem()!!
            assertEquals("The Matrix Reloaded", entry.title)
            assertEquals("Holds up on rewatch", entry.note)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeEntry_is_null_for_an_unsaved_title() = runTest {
        repository.observeEntry(MediaId.tmdbMovie("999")).test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeEntry_is_null_after_remove() = runTest {
        repository.upsertSnapshot(details())
        repository.remove(MediaId.tmdbMovie("603"))

        repository.observeEntry(MediaId.tmdbMovie("603")).test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeEntry_seenEpisodes_reflects_progress_api() = runTest {
        val id = MediaId.tmdbMovie("603")
        repository.upsertSnapshot(details(id))

        repository.observeEntry(id).test {
            val notStarted = awaitItem()!!
            assertEquals(0, notStarted.seenEpisodes)
            assertEquals(WatchStatus.NOT_STARTED, notStarted.status)

            progressApi.setSeen(id, setOf(EpisodeId.forMovie(id)))
            val watched = awaitItem()!!
            assertEquals(1, watched.seenEpisodes)
            assertEquals(WatchStatus.WATCHED, watched.status)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeAll_seenEpisodes_reacts_to_progress_ticks_per_entry() = runTest {
        val show = MediaId.tmdbTv("1399")
        val movie = MediaId.tmdbMovie("603")
        repository.upsertSnapshot(details(show, productionStatus = ProductionStatus.RETURNING))
        repository.upsertSnapshot(details(movie))

        repository.observeAll()
            .map { entries -> entries.associate { it.mediaId to it.seenEpisodes } }
            .test {
                assertEquals(mapOf(show to 0, movie to 0), awaitItem())

                progressApi.setSeen(movie, setOf(EpisodeId.forMovie(movie)))
                assertEquals(mapOf(show to 0, movie to 1), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
    }
}
