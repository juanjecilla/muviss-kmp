package com.codingpit.muviss.feature.nextpick.data

import com.codingpit.muviss.feature.nextpick.api.PickCriteria
import com.codingpit.muviss.feature.nextpick.domain.PickHistory
import com.codingpit.muviss.feature.nextpick.domain.PickHistoryReader
import com.codingpit.muviss.feature.settings.api.TasteItem
import com.codingpit.muviss.feature.settings.api.TasteKind
import com.codingpit.muviss.models.GenreKey
import com.codingpit.muviss.models.MediaType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TasteSignalsTest {

    private val netflix = TasteItem(TasteKind.SERVICE, "8", "Netflix")
    private val disney = TasteItem(TasteKind.SERVICE, "337", "Disney+")
    private val horror = genreItem(MediaType.MOVIE, "27")
    private val drama = genreItem(MediaType.TV, "18")

    @Test
    fun the_services_are_their_tmdb_provider_ids() = runTest {
        assertEquals(setOf(8, 337), serviceIds(FakeTasteProfile(listOf(netflix, disney, horror))))
    }

    @Test
    fun a_service_value_that_is_not_an_id_is_skipped_not_guessed() = runTest {
        assertEquals(emptySet(), serviceIds(FakeTasteProfile(listOf(TasteItem(TasteKind.SERVICE, "netflix", "Netflix")))))
    }

    @Test
    fun the_liked_genres_are_muted_genre_keys_and_other_kinds_are_left_out() = runTest {
        val liked = likedGenreKeys(FakeTasteProfile(listOf(horror, drama, netflix, TasteItem(TasteKind.GENRE, "movie:", "broken"))))
        assertEquals(setOf(GenreKey(MediaType.MOVIE, "27"), GenreKey(MediaType.TV, "18")), liked)
    }

    @Test
    fun the_history_reader_adds_the_liked_genres_to_what_the_library_reader_built() = runTest {
        val inner = PickHistoryReader { PickHistory(genreRatings = mapOf("27" to 8.0), todayEpochDay = 9) }
        val history = TasteHistoryReader(inner, { FakeTasteProfile(listOf(horror)) }).read(PickCriteria())

        assertEquals(setOf(GenreKey(MediaType.MOVIE, "27")), history.likedGenres)
        assertEquals(mapOf("27" to 8.0), history.genreRatings)
        assertEquals(9L, history.todayEpochDay)
    }
}

private fun genreItem(type: MediaType, id: String) = TasteItem(TasteKind.GENRE, "${type.wireName}:$id", "Genre $id")
