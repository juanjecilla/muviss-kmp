package com.codingpit.muviss.feature.settings.domain

import com.codingpit.muviss.feature.settings.api.TasteItem
import com.codingpit.muviss.feature.settings.api.TasteKind
import com.codingpit.muviss.models.GenreKey
import com.codingpit.muviss.models.MediaType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeTasteRepository : TasteProfileRepository {
    val writes = mutableListOf<Pair<TasteItem, Boolean>>()
    override fun observe(): Flow<List<TasteItem>> = flowOf(emptyList())
    override suspend fun setLiked(item: TasteItem, liked: Boolean) {
        writes += item to liked
    }
}

class TasteProfileDomainTest {

    private val action = TasteItem(TasteKind.GENRE, "movie:28", "Action")
    private val talk = TasteItem(TasteKind.GENRE, "tv:10767", "Talk")
    private val netflix = TasteItem(TasteKind.SERVICE, "8", "Netflix")

    @Test
    fun item_id_is_kind_colon_value_and_matches_the_stored_key() {
        assertEquals("GENRE:movie:28", action.id)
        assertEquals("SERVICE:8", netflix.id)
    }

    @Test
    fun a_genre_item_parses_to_the_muted_genre_key_it_stands_for() {
        assertEquals(GenreKey(MediaType.MOVIE, "28"), TasteRules.genreKeyOf(action))
        assertEquals(GenreKey(MediaType.TV, "10767"), TasteRules.genreKeyOf(talk))
    }

    @Test
    fun a_malformed_genre_value_has_no_key_and_is_never_refused() {
        val malformed = TasteItem(TasteKind.GENRE, "tv", "Talk")
        assertNull(TasteRules.genreKeyOf(malformed))
        assertTrue(TasteRules.canLike(malformed, setOf(GenreKey(MediaType.TV, "10767"))))
    }

    @Test
    fun a_muted_genre_cannot_be_liked() {
        assertFalse(TasteRules.canLike(talk, setOf(GenreKey(MediaType.TV, "10767"))))
    }

    @Test
    fun the_same_numeric_genre_of_the_other_type_is_not_refused() {
        // TMDB reuses numbers across movie and TV genres, so muting TV 10767 must not block movie 10767.
        assertTrue(TasteRules.canLike(TasteItem(TasteKind.GENRE, "movie:10767", "X"), setOf(GenreKey(MediaType.TV, "10767"))))
    }

    @Test
    fun non_genre_kinds_are_never_refused_by_muting() {
        assertTrue(TasteRules.canLike(netflix, setOf(GenreKey(MediaType.TV, "8"))))
    }

    @Test
    fun liking_a_muted_genre_is_refused_and_writes_nothing() = runTest {
        val repository = FakeTasteRepository()
        val useCase = SetTasteItemUseCase(repository, flowOf(setOf(GenreKey(MediaType.TV, "10767"))))

        val accepted = useCase(talk, liked = true)

        assertFalse(accepted)
        assertEquals(emptyList(), repository.writes)
    }

    @Test
    fun liking_a_genre_that_was_muted_a_moment_ago_is_accepted_once_unmuted() = runTest {
        val repository = FakeTasteRepository()
        val muted = MutableStateFlow(setOf(GenreKey(MediaType.TV, "10767")))
        val useCase = SetTasteItemUseCase(repository, muted)
        assertFalse(useCase(talk, liked = true))

        muted.value = emptySet()

        assertTrue(useCase(talk, liked = true))
        assertEquals(listOf(talk to true), repository.writes)
    }

    @Test
    fun unliking_is_never_refused_even_for_a_muted_genre() = runTest {
        val repository = FakeTasteRepository()
        val useCase = SetTasteItemUseCase(repository, flowOf(setOf(GenreKey(MediaType.TV, "10767"))))

        assertTrue(useCase(talk, liked = false))
        assertEquals(listOf(talk to false), repository.writes)
    }

    @Test
    fun the_muted_set_is_read_when_the_write_happens() = runTest {
        val muted = MutableStateFlow<Set<GenreKey>>(emptySet())
        val useCase = SetTasteItemUseCase(FakeTasteRepository(), muted)
        assertEquals(emptySet(), muted.first())
        assertTrue(useCase(action, liked = true))
    }
}
