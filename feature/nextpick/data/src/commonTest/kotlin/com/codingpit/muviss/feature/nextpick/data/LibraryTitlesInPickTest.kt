package com.codingpit.muviss.feature.nextpick.data

import com.codingpit.muviss.feature.nextpick.api.PickCriteria
import com.codingpit.muviss.feature.nextpick.api.PickMood
import com.codingpit.muviss.feature.nextpick.domain.PickContext
import com.codingpit.muviss.feature.nextpick.domain.PickReason
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.GenreKey
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.MutedGenres
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Library titles reach Muted Genres and mood matching through their mapped genre ids (#547, ADR 0031, ADR 0032). */
class LibraryTitlesInPickTest {

    @Test
    fun muted_talk_show_from_the_library_is_not_offered() = runTest {
        val engine = libraryEngine(
            library = listOf(
                libraryTitle("1", "Late Night Show", MediaType.TV, genres = listOf("Talk")),
                libraryTitle("2", "Drama Show", MediaType.TV, genres = listOf("Drama")),
            ),
            muted = MutedGenres(setOf(GenreKey(MediaType.TV, "10767"))),
        )

        val titles = engine.pick(PickCriteria(), PickContext(online = true)).picks.map { it.candidate.summary.title }

        assertEquals(listOf("Drama Show"), titles)
    }

    @Test
    fun mood_match_fires_for_a_library_title() = runTest {
        val engine = libraryEngine(
            library = listOf(
                libraryTitle("1", "Comedy Night", genres = listOf("Comedy")),
                libraryTitle("2", "Drama Film", genres = listOf("Drama")),
            ),
        )

        val top = engine.pick(PickCriteria(moods = setOf(PickMood.LIGHT)), PickContext(online = true)).picks.first()

        assertEquals("Comedy Night", top.candidate.summary.title)
        assertEquals(PickReason.FitsMood(PickMood.LIGHT), top.reason)
    }

    @Test
    fun unmapped_genre_name_is_tolerated_and_the_title_is_still_offered() = runTest {
        val engine = libraryEngine(
            library = listOf(libraryTitle("1", "Odd Film", genres = listOf("Not A Real Genre"))),
            live = { error("catalogue unavailable") },
        )

        val picks = engine.pick(PickCriteria(), PickContext(online = true)).picks

        assertEquals(listOf("Odd Film"), picks.map { it.candidate.summary.title })
        assertTrue(picks.single().candidate.summary.genreIds.isEmpty())
    }

    @Test
    fun spanish_stored_name_is_muted_offline_through_the_bundled_names() = runTest {
        val engine = libraryEngine(
            library = listOf(
                libraryTitle("1", "Sci Fi Film", genres = listOf("Ciencia ficción")),
                libraryTitle("2", "Drama Film", genres = listOf("Drama")),
            ),
            muted = MutedGenres(setOf(GenreKey(MediaType.MOVIE, "878"))),
            language = "en-US",
            online = false,
        )

        val titles = engine.pick(PickCriteria(), PickContext(online = false)).picks.map { it.candidate.summary.title }

        assertEquals(listOf("Drama Film"), titles)
    }

    @Test
    fun english_stored_name_is_muted_while_the_live_catalogue_is_spanish() = runTest {
        val spanishLive: suspend (MediaType) -> List<Genre> = { listOf(Genre("35", "Comedia")) }
        val engine = libraryEngine(
            library = listOf(
                libraryTitle("1", "Comedy Night", genres = listOf("Comedy")),
                libraryTitle("2", "Drama Film", genres = listOf("Drama")),
            ),
            muted = MutedGenres(setOf(GenreKey(MediaType.MOVIE, "35"))),
            live = spanishLive,
            language = "es-ES",
        )

        val titles = engine.pick(PickCriteria(), PickContext(online = true)).picks.map { it.candidate.summary.title }

        assertEquals(listOf("Drama Film"), titles)
    }

    @Test
    fun same_name_maps_per_type_so_a_movie_is_not_muted_by_a_tv_genre() = runTest {
        val engine = libraryEngine(
            library = listOf(libraryTitle("1", "Adventure Film", genres = listOf("Action & Adventure"))),
            muted = MutedGenres(setOf(GenreKey(MediaType.TV, "10759"))),
        )

        val titles = engine.pick(PickCriteria(), PickContext(online = false)).picks.map { it.candidate.summary.title }

        assertEquals(listOf("Adventure Film"), titles)
    }

    @Test
    fun library_ratings_average_per_mapped_genre_id_for_genre_affinity() = runTest {
        val library = FakeLibrary(
            listOf(
                libraryTitle("1", "A", genres = listOf("Comedy"), rating = 8),
                libraryTitle("2", "B", genres = listOf("Comedy"), rating = 6),
                libraryTitle("3", "C", genres = listOf("Drama")),
            ),
        )
        val genreIds = LibraryGenreIds(liveCatalogue = noLiveCatalogue, language = { "en-US" })

        val history = CollectionHistoryReader(FixedClock, { library }, connectivity(true), genreIds).read(PickCriteria())

        assertEquals(7.0, history.genreRatings["35"])
        assertEquals(null, history.genreRatings["18"])
    }
}
