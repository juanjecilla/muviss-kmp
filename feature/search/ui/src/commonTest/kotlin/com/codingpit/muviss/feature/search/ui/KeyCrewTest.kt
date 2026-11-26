package com.codingpit.muviss.feature.search.ui

import com.codingpit.muviss.models.Credit
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.PersonId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeyCrewTest {

    private fun person(id: String, name: String, job: String?, department: String? = null) = Credit(PersonId.tmdb(id), name, job = job, department = department)

    private fun movie(vararg crew: Credit) = MediaDetails(MediaSummary(MediaId.tmdbMovie("1"), "Movie"), crew = crew.toList())

    private fun show(vararg crew: Credit) = MediaDetails(MediaSummary(MediaId.tmdbTv("1"), "Show"), crew = crew.toList())

    @Test
    fun a_movie_names_only_its_directors() {
        val details = movie(person("1", "Dir", "Director"), person("2", "Writer", "Writer"), person("3", "Other Dir", "Director"))

        assertEquals(listOf("Dir", "Other Dir"), details.keyCrew().map { it.name })
    }

    @Test
    fun a_show_names_only_its_creators() {
        val details = show(person("1", "Maker", Credit.CREATOR_JOB), person("2", "Director", "Director"))

        assertEquals(listOf("Maker"), details.keyCrew().map { it.name })
    }

    @Test
    fun a_person_credited_twice_is_named_once() {
        val details = movie(person("1", "Dir", "Director", "Directing"), person("1", "Dir", "Director", "Production"))

        assertEquals(1, details.keyCrew().size)
    }

    @Test
    fun a_title_with_no_credits_has_none_to_show() {
        assertFalse(movie().hasCredits())
        assertTrue(movie(person("1", "Dir", "Director")).hasCredits())
    }
}
