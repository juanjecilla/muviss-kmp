package com.codingpit.muviss.feature.progress.domain

import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SetMovieWatchedUseCaseTest {

    private val movie = MediaId.tmdbMovie("603")
    private val show = MediaId.tmdbTv("1399")

    @Test
    fun movie_id_writes_the_single_movie_tick() = runTest {
        val repository = FakeProgressRepository()
        val useCase = SetMovieWatchedUseCase(repository)

        useCase(movie, watched = true)

        assertEquals(listOf(EpisodeId.forMovie(movie) to true), repository.tickedEpisodes)
    }

    @Test
    fun movie_id_unwatch_writes_the_single_movie_untick() = runTest {
        val repository = FakeProgressRepository()
        val useCase = SetMovieWatchedUseCase(repository)

        useCase(movie, watched = false)

        assertEquals(listOf(EpisodeId.forMovie(movie) to false), repository.tickedEpisodes)
    }

    @Test
    fun tv_id_is_rejected_before_anything_is_written() = runTest {
        val repository = FakeProgressRepository()
        val useCase = SetMovieWatchedUseCase(repository)

        assertFailsWith<IllegalArgumentException> { useCase(show, watched = true) }

        assertTrue(repository.tickedEpisodes.isEmpty(), "a TV id must never write a synthetic 0/0 episode")
    }

    @Test
    fun tv_id_unwatch_is_rejected_before_anything_is_written() = runTest {
        val repository = FakeProgressRepository()
        val useCase = SetMovieWatchedUseCase(repository)

        assertFailsWith<IllegalArgumentException> { useCase(show, watched = false) }

        assertTrue(repository.tickedEpisodes.isEmpty())
    }
}
