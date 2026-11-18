package com.codingpit.muviss.core.network.tmdb

import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The fixtures follow TMDB's documented `/images` shape; this suite never calls the live API. */
class TmdbImagesTest {

    private val json = """
        {"id":1399,
         "backdrops":[
           {"file_path":"/low.jpg","aspect_ratio":1.778,"vote_average":3.0,"iso_639_1":null},
           {"file_path":"/high.jpg","aspect_ratio":1.778,"vote_average":5.5,"iso_639_1":null},
           {"file_path":"/high.jpg","aspect_ratio":1.778,"vote_average":5.5,"iso_639_1":"en"}],
         "posters":[{"file_path":"/poster.jpg","aspect_ratio":0.667,"vote_average":5.0,"iso_639_1":"en"}],
         "stills":[{"file_path":"/still.jpg","aspect_ratio":1.778,"vote_average":4.0}]}
    """.trimIndent()

    @Test
    fun the_best_voted_backdrop_comes_first_once_each_at_w1280() = runTest {
        val fixture = ProviderFixture(handler = { respondJson(json) })

        val images = fixture.provider.images(MediaId.tmdbTv("1399"))

        assertEquals(
            listOf("https://image.tmdb.org/t/p/w1280/high.jpg", "https://image.tmdb.org/t/p/w1280/low.jpg"),
            images.backdrops.map { it.url },
        )
    }

    @Test
    fun posters_come_at_w780() = runTest {
        val fixture = ProviderFixture(handler = { respondJson(json) })

        val images = fixture.provider.images(MediaId.tmdbMovie("603"))

        assertEquals(listOf("https://image.tmdb.org/t/p/w780/poster.jpg"), images.posters.map { it.url })
    }

    @Test
    fun the_request_asks_for_the_bare_language_code_plain_art_and_english() = runTest {
        val fixture = ProviderFixture(handler = { respondJson(json) })

        fixture.provider.images(MediaId.tmdbTv("1399"))

        val request = fixture.requests.single()
        assertTrue(request.url.encodedPath.endsWith("/tv/1399/images"))
        assertEquals("en,null", request.url.parameters["include_image_language"])
    }

    @Test
    fun a_movie_uses_the_movie_endpoint() = runTest {
        val fixture = ProviderFixture(handler = { respondJson(json) })

        fixture.provider.images(MediaId.tmdbMovie("603"))

        assertTrue(fixture.requests.single().url.encodedPath.endsWith("/movie/603/images"))
    }

    @Test
    fun an_episodes_stills_come_from_its_own_endpoint() = runTest {
        val fixture = ProviderFixture(handler = { respondJson(json) })

        val images = fixture.provider.episodeImages(EpisodeId(MediaId.tmdbTv("1399"), 2, 5))

        assertTrue(fixture.requests.single().url.encodedPath.endsWith("/tv/1399/season/2/episode/5/images"))
        assertEquals(listOf("https://image.tmdb.org/t/p/w1280/still.jpg"), images.stills.map { it.url })
    }
}
