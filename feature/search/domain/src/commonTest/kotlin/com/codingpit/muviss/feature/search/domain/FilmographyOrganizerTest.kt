package com.codingpit.muviss.feature.search.domain

import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.PersonCredit
import com.codingpit.muviss.models.PersonDetails
import com.codingpit.muviss.models.PersonId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilmographyOrganizerTest {

    private val today = 20_000L

    private fun title(id: String) = MediaSummary(MediaId.tmdbMovie(id), "Title $id")

    private fun acted(id: String, character: String? = null, day: Long? = null, votes: Int = 10) = PersonCredit(title(id), character = character, acting = true, releaseEpochDay = day, voteCount = votes)

    private fun made(id: String, job: String, day: Long? = null, votes: Int = 10) = PersonCredit(title(id), job = job, acting = false, releaseEpochDay = day, voteCount = votes)

    private fun person(department: String?, vararg credits: PersonCredit) = PersonDetails(PersonId.tmdb("1"), "P", knownForDepartment = department, credits = credits.toList())

    private fun ids(entries: List<FilmographyEntry>) = entries.map { it.title.id.external }

    @Test
    fun a_title_with_two_jobs_is_one_row_with_both_roles() {
        val result = FilmographyOrganizer.organize(person("Directing", made("1", "Director", 100), made("1", "Writer", 100)), today)

        assertEquals(1, result.crew.size)
        assertEquals(listOf("Director", "Writer"), result.crew.single().roles)
    }

    @Test
    fun the_same_job_listed_twice_is_one_role() {
        val result = FilmographyOrganizer.organize(person("Directing", made("1", "Producer", 100), made("1", "Producer", 100)), today)

        assertEquals(listOf("Producer"), result.crew.single().roles)
    }

    @Test
    fun a_title_acted_in_and_made_is_in_both_tabs_with_each_tabs_own_roles() {
        val result = FilmographyOrganizer.organize(person("Acting", acted("1", "Cobb", 100), made("1", "Producer", 100)), today)

        assertEquals(listOf("Cobb"), result.acting.single().roles)
        assertEquals(listOf("Producer"), result.crew.single().roles)
        assertTrue(result.hasBothTabs)
    }

    @Test
    fun released_titles_come_newest_first() {
        val result = FilmographyOrganizer.organize(person("Acting", acted("old", day = 100), acted("new", day = 900), acted("mid", day = 500)), today)

        assertEquals(listOf("new", "mid", "old"), ids(result.acting))
    }

    @Test
    fun upcoming_titles_lead_soonest_first_and_are_flagged() {
        val result = FilmographyOrganizer.organize(person("Acting", acted("out", day = 900), acted("later", day = today + 300), acted("soon", day = today + 10)), today)

        assertEquals(listOf("soon", "later", "out"), ids(result.acting))
        assertEquals(listOf(true, true, false), result.acting.map { it.upcoming })
    }

    @Test
    fun a_title_released_today_is_not_upcoming() {
        val result = FilmographyOrganizer.organize(person("Acting", acted("1", day = today)), today)

        assertFalse(result.acting.single().upcoming)
    }

    @Test
    fun undated_titles_come_last_and_are_not_upcoming() {
        val result = FilmographyOrganizer.organize(person("Acting", acted("undated"), acted("out", day = 900)), today)

        assertEquals(listOf("out", "undated"), ids(result.acting))
        assertFalse(result.acting.last().upcoming)
    }

    @Test
    fun known_for_is_the_most_voted_released_titles_of_the_main_department() {
        val acting = (1..12).map { acted("a$it", day = 100L + it, votes = it * 10) }
        val result = FilmographyOrganizer.organize(person("Acting", *(acting + made("c1", "Director", 100, votes = 9_999)).toTypedArray()), today)

        assertEquals(FilmographyOrganizer.KNOWN_FOR_SIZE, result.knownFor.size)
        assertEquals("a12", result.knownFor.first().title.id.external)
        assertFalse(result.knownFor.any { it.title.id.external == "c1" })
    }

    @Test
    fun known_for_leaves_out_upcoming_and_unvoted_titles() {
        val result = FilmographyOrganizer.organize(person("Acting", acted("future", day = today + 5, votes = 500), acted("unvoted", day = 100, votes = 0), acted("seen", day = 100, votes = 3)), today)

        assertEquals(listOf("seen"), ids(result.knownFor))
    }

    @Test
    fun known_for_leaves_out_undated_titles_however_voted() {
        val result = FilmographyOrganizer.organize(person("Acting", acted("undated", votes = 9_999), acted("dated", day = 100, votes = 3)), today)

        assertEquals(listOf("dated"), ids(result.knownFor))
    }

    @Test
    fun known_for_keeps_a_title_released_today() {
        val result = FilmographyOrganizer.organize(person("Acting", acted("today", day = today, votes = 7)), today)

        assertEquals(listOf("today"), ids(result.knownFor))
    }

    @Test
    fun a_director_opens_on_the_crew_tab_and_known_for_reads_from_it() {
        val result = FilmographyOrganizer.organize(person("Directing", acted("1", day = 100, votes = 50), made("2", "Director", 100, votes = 5)), today)

        assertFalse(result.startsOnActing)
        assertEquals(listOf("2"), ids(result.knownFor))
    }

    @Test
    fun a_person_with_only_one_kind_of_credit_opens_on_it_whatever_the_source_says() {
        val onlyActing = FilmographyOrganizer.organize(person("Directing", acted("1", day = 100)), today)
        val onlyCrew = FilmographyOrganizer.organize(person("Acting", made("1", "Editor", 100)), today)

        assertTrue(onlyActing.startsOnActing)
        assertFalse(onlyCrew.startsOnActing)
        assertFalse(onlyActing.hasBothTabs)
    }

    @Test
    fun a_person_with_no_credits_has_an_empty_filmography() {
        val result = FilmographyOrganizer.organize(person(null), today)

        assertTrue(result.acting.isEmpty() && result.crew.isEmpty() && result.knownFor.isEmpty())
    }
}
