package com.codingpit.muviss.core.network.tmdb

import com.codingpit.muviss.core.network.MetadataLocale
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MetadataError
import com.codingpit.muviss.models.PersonId
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fixtures follow TMDB's documented `/person/{id}` shape with `combined_credits`, not a live capture. */
class TmdbPersonTest {

    private val personJson = """{"id":6193,"name":"Some Person","biography":"Bio text.","birthday":"1974-11-11","place_of_birth":"Los Angeles",
        "profile_path":"/p.jpg","known_for_department":"Acting",
        "external_ids":{"imdb_id":"nm0000138","wikidata_id":"Q38111"},
        "combined_credits":{
          "cast":[
            {"id":27205,"media_type":"movie","title":"Inception","release_date":"2010-07-15","vote_count":30000,"character":"Cobb"},
            {"id":1399,"media_type":"tv","name":"Show","first_air_date":"2011-04-17","vote_count":20000,"character":"Lead","episode_count":73,"genre_ids":[18]},
            {"id":77,"media_type":"tv","name":"Late Night","first_air_date":"2015-01-01","vote_count":5,"character":"Self","episode_count":1,"genre_ids":[10767]},
            {"id":5,"media_type":"person","name":"Not A Title"},
            {"id":6,"media_type":"movie","title":""}],
          "crew":[
            {"id":603,"media_type":"movie","title":"The Matrix","release_date":"1999-03-31","vote_count":25000,"job":"Producer","department":"Production"}]}}"""

    private val id = PersonId.tmdb("6193")

    @Test
    fun one_request_asks_for_the_filmography_and_the_external_ids() = runTest {
        val fixture = ProviderFixture(handler = { respondJson(personJson) })

        fixture.provider.person(id)

        assertEquals(1, fixture.requests.size)
        assertEquals("/3/person/6193", fixture.requests.single().url.encodedPath)
        assertEquals("combined_credits,external_ids", fixture.requests.single().url.parameters["append_to_response"])
    }

    @Test
    fun the_person_and_their_ids_are_mapped() = runTest {
        val person = ProviderFixture(handler = { respondJson(personJson) }).provider.person(id)

        assertEquals("Some Person", person.name)
        assertEquals("Bio text.", person.biography)
        assertEquals("Acting", person.knownForDepartment)
        assertEquals("nm0000138", person.anchors.imdbId)
        assertEquals("Q38111", person.anchors.wikidataId)
        assertTrue(person.profileUrl!!.endsWith("/p.jpg"))
    }

    @Test
    fun cast_and_crew_credits_are_mapped_and_a_non_title_or_nameless_row_is_dropped() = runTest {
        val person = ProviderFixture(handler = { respondJson(personJson) }).provider.person(id)

        val titles = person.credits.map { it.title.title to it.acting }
        assertEquals(listOf("Inception" to true, "Show" to true, "The Matrix" to false), titles)
        assertEquals(MediaId.tmdbMovie("27205"), person.credits.first().title.id)
        assertEquals("Cobb", person.credits.first().character)
        assertEquals(73, person.credits[1].episodeCount)
        assertEquals("Producer", person.credits.last().job)
        assertEquals(TmdbMapper.airDateToEpochDay("2010-07-15"), person.credits.first().releaseEpochDay)
    }

    @Test
    fun a_talk_show_appearance_is_not_a_credit() = runTest {
        val person = ProviderFixture(handler = { respondJson(personJson) }).provider.person(id)

        assertTrue(person.credits.none { it.title.title == "Late Night" })
    }

    @Test
    fun an_empty_biography_in_english_costs_no_second_request() = runTest {
        val fixture = ProviderFixture(handler = { respondJson("""{"id":6193,"name":"X","biography":""}""") })

        val person = fixture.provider.person(id)

        assertEquals(1, fixture.requests.size)
        assertNull(person.biography)
    }

    @Test
    fun an_empty_biography_in_another_language_falls_back_to_english_once() = runTest {
        val spanish = object : MetadataLocale {
            override val language = "es-ES"
            override val region = "ES"
        }
        val fixture = ProviderFixture(locale = spanish) { request ->
            if (request.url.parameters["language"] == "en-US") respondJson("""{"id":6193,"name":"X","biography":"English bio"}""") else respondJson("""{"id":6193,"name":"X","biography":""}""")
        }

        val person = fixture.provider.person(id)

        assertEquals(2, fixture.requests.size)
        assertEquals("English bio", person.biography)
    }

    @Test
    fun a_failing_english_fallback_still_shows_the_page() = runTest {
        val spanish = object : MetadataLocale {
            override val language = "es-ES"
            override val region = "ES"
        }
        val fixture = ProviderFixture(locale = spanish) { request ->
            if (request.url.parameters["language"] == "en-US") respondJson("{}", HttpStatusCode.NotFound) else respondJson("""{"id":6193,"name":"X","biography":""}""")
        }

        val person = fixture.provider.person(id)

        assertEquals("X", person.name)
        assertNull(person.biography)
    }

    @Test
    fun an_unknown_person_is_not_found() = runTest {
        val fixture = ProviderFixture(handler = { respondJson("{}", HttpStatusCode.NotFound) })

        assertFailsWith<MetadataError.NotFound> { fixture.provider.person(id) }
    }
}
