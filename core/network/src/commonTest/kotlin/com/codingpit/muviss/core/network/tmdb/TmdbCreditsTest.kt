package com.codingpit.muviss.core.network.tmdb

import com.codingpit.muviss.models.Credit
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.PersonId
import io.ktor.client.engine.mock.MockRequestHandler
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cast and crew ride on the details request already made, so the request count
 * of a title must not move. Fixtures follow TMDB's documented shapes
 * (`credits` on a movie, `aggregate_credits` + `created_by` on a show), not a
 * live capture.
 */
class TmdbCreditsTest {

    private val movieJson = """{"id":27205,"title":"Inception","external_ids":{"imdb_id":"tt1375666","wikidata_id":"Q25188"},
        "credits":{
          "cast":[
            {"id":2,"name":"Third","order":2,"character":"C"},
            {"id":1,"name":"First","order":0,"character":"A","profile_path":"/a.jpg"},
            {"id":3,"name":"","order":1,"character":"B"}],
          "crew":[
            {"id":9,"name":"Director Person","job":"Director","department":"Directing","profile_path":"/d.jpg"},
            {"id":10,"name":"Writer Person","job":"Writer","department":"Writing"}]}}"""

    private val tvJson = """{"id":1399,"name":"Show","number_of_seasons":0,"seasons":[],
        "external_ids":{"imdb_id":"tt0944947","wikidata_id":"Q23572"},
        "created_by":[{"id":7,"name":"Creator Person","profile_path":"/c.jpg"}],
        "aggregate_credits":{
          "cast":[
            {"id":5,"name":"Lead","order":0,"total_episode_count":73,
             "roles":[{"character":"Minor","episode_count":3},{"character":"Main","episode_count":70}]},
            {"id":6,"name":"Voice","order":1,"roles":[]}],
          "crew":[
            {"id":8,"name":"Composer Person","department":"Sound","total_episode_count":73,
             "jobs":[{"job":"Original Music Composer","episode_count":73}]}]}}"""

    private fun handler(body: String): MockRequestHandler = { respondJson(body) }

    @Test
    fun a_movie_costs_one_request_and_asks_for_credits() = runTest {
        val fixture = ProviderFixture(handler = handler(movieJson))

        fixture.provider.details(MediaId.tmdbMovie("27205"))

        assertEquals(1, fixture.requests.size)
        assertEquals("external_ids,credits", fixture.requests.single().url.parameters["append_to_response"])
    }

    @Test
    fun a_show_asks_for_whole_run_credits_and_still_costs_one_request_without_seasons() = runTest {
        val fixture = ProviderFixture(handler = handler(tvJson))

        fixture.provider.details(MediaId.tmdbTv("1399"))

        assertEquals(1, fixture.requests.size)
        assertEquals("external_ids,aggregate_credits", fixture.requests.single().url.parameters["append_to_response"])
    }

    @Test
    fun movie_cast_is_in_billing_order_and_skips_a_nameless_row() = runTest {
        val details = ProviderFixture(handler = handler(movieJson)).provider.details(MediaId.tmdbMovie("27205"))

        assertEquals(listOf("First", "Third"), details.cast.map { it.name })
        assertEquals(PersonId.tmdb("1"), details.cast.first().personId)
        assertEquals("A", details.cast.first().character)
        assertTrue(details.cast.first().profileUrl!!.endsWith("/a.jpg"))
        assertNull(details.cast.first().episodeCount)
    }

    @Test
    fun movie_crew_keeps_job_and_department() = runTest {
        val details = ProviderFixture(handler = handler(movieJson)).provider.details(MediaId.tmdbMovie("27205"))

        val director = details.crew.single { it.job == "Director" }
        assertEquals("Directing", director.department)
        assertEquals(PersonId.tmdb("9"), director.personId)
    }

    @Test
    fun the_wikidata_and_imdb_ids_reach_the_anchors() = runTest {
        val details = ProviderFixture(handler = handler(movieJson)).provider.details(MediaId.tmdbMovie("27205"))

        assertEquals("tt1375666", details.anchors.imdbId)
        assertEquals("Q25188", details.anchors.wikidataId)
    }

    @Test
    fun show_cast_lists_the_most_played_character_first_with_the_whole_run_episode_count() = runTest {
        val details = ProviderFixture(handler = handler(tvJson)).provider.details(MediaId.tmdbTv("1399"))

        val lead = details.cast.first()
        assertEquals("Main / Minor", lead.character)
        assertEquals(73, lead.episodeCount)
    }

    @Test
    fun a_show_cast_member_without_roles_has_no_character_and_no_count() = runTest {
        val details = ProviderFixture(handler = handler(tvJson)).provider.details(MediaId.tmdbTv("1399"))

        val voice = details.cast.last()
        assertNull(voice.character)
        assertNull(voice.episodeCount)
    }

    @Test
    fun show_creators_come_first_in_the_crew() = runTest {
        val details = ProviderFixture(handler = handler(tvJson)).provider.details(MediaId.tmdbTv("1399"))

        assertEquals(listOf("Creator Person", "Composer Person"), details.crew.map { it.name })
        assertEquals(Credit.CREATOR_JOB, details.crew.first().job)
        assertEquals("Sound", details.crew.last().department)
    }

    @Test
    fun a_title_with_no_credits_block_has_an_empty_cast_and_crew() = runTest {
        val details = ProviderFixture(handler = handler("""{"id":1,"title":"Bare"}""")).provider.details(MediaId.tmdbMovie("1"))

        assertTrue(details.cast.isEmpty())
        assertTrue(details.crew.isEmpty())
    }

    @Test
    fun cast_is_capped() = runTest {
        val cast = (0 until 300).joinToString(",") { """{"id":$it,"name":"P$it","order":$it}""" }
        val body = """{"id":1,"title":"Big","credits":{"cast":[$cast],"crew":[]}}"""

        val details = ProviderFixture(handler = handler(body)).provider.details(MediaId.tmdbMovie("1"))

        assertEquals(TmdbMapper.MAX_CAST, details.cast.size)
    }
}
