package com.codingpit.muviss.feature.nextpick.data

import com.codingpit.muviss.feature.nextpick.api.PickCompany
import com.codingpit.muviss.feature.nextpick.api.PickCriteria
import com.codingpit.muviss.feature.nextpick.domain.PickHistory
import com.codingpit.muviss.feature.nextpick.domain.PickHistoryReader
import com.codingpit.muviss.feature.nextpick.domain.PickQuestion
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

    @Test
    fun one_liked_movie_format_answers_the_format_question_with_a_movie() {
        val answers = profileAnswers(listOf(TasteItem(TasteKind.FORMAT, "MOVIE", "Movie")))
        assertEquals(setOf(PickQuestion.FORMAT_AND_TIME), answers.answered)
        assertEquals(MediaType.MOVIE, answers.criteria.type)
    }

    @Test
    fun a_series_or_a_limited_series_is_a_show_and_one_of_them_answers_the_format_question() {
        assertEquals(MediaType.TV, profileAnswers(listOf(TasteItem(TasteKind.FORMAT, "SERIES", "Series"))).criteria.type)
        val limited = profileAnswers(listOf(TasteItem(TasteKind.FORMAT, "LIMITED_SERIES", "Limited")))
        assertEquals(MediaType.TV, limited.criteria.type)
        assertEquals(setOf(PickQuestion.FORMAT_AND_TIME), limited.answered)
    }

    @Test
    fun two_liked_formats_leave_the_format_question_open() {
        val answers = profileAnswers(listOf(TasteItem(TasteKind.FORMAT, "MOVIE", "Movie"), TasteItem(TasteKind.FORMAT, "SERIES", "Series")))
        assertEquals(emptySet(), answers.answered)
        assertEquals(null, answers.criteria.type)
    }

    @Test
    fun a_format_value_the_rule_does_not_know_is_skipped() {
        assertEquals(emptySet(), profileAnswers(listOf(TasteItem(TasteKind.FORMAT, "PODCAST", "Podcast"))).answered)
    }

    @Test
    fun one_liked_language_sets_the_original_language_and_answers_nothing_else() {
        val answers = profileAnswers(listOf(TasteItem(TasteKind.LANGUAGE, "es", "Espanol")))
        assertEquals("es", answers.criteria.originalLanguage)
        assertEquals(emptySet(), answers.answered)
    }

    @Test
    fun two_liked_languages_leave_the_language_open() {
        val answers = profileAnswers(listOf(TasteItem(TasteKind.LANGUAGE, "es", "Espanol"), TasteItem(TasteKind.LANGUAGE, "fr", "Francais")))
        assertEquals(null, answers.criteria.originalLanguage)
    }

    @Test
    fun one_liked_company_answers_the_company_question() {
        val answers = profileAnswers(listOf(TasteItem(TasteKind.COMPANY, "PARTNER", "PARTNER")))
        assertEquals(setOf(PickQuestion.COMPANY), answers.answered)
        assertEquals(PickCompany.PARTNER, answers.criteria.company)
    }

    @Test
    fun two_liked_companies_leave_the_company_question_open() {
        val answers = profileAnswers(listOf(TasteItem(TasteKind.COMPANY, "ALONE", "ALONE"), TasteItem(TasteKind.COMPANY, "FAMILY", "FAMILY")))
        assertEquals(emptySet(), answers.answered)
        assertEquals(null, answers.criteria.company)
    }

    @Test
    fun an_empty_profile_answers_nothing() {
        assertEquals(ProfileAnswers(emptySet(), PickCriteria()), profileAnswers(emptyList()))
    }
}

private fun genreItem(type: MediaType, id: String) = TasteItem(TasteKind.GENRE, "${type.wireName}:$id", "Genre $id")
