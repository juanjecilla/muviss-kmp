package com.codingpit.muviss.feature.settings.domain

import com.codingpit.muviss.models.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TraktImportParserTest {

    private val parser = TraktImportParser()

    @Test
    fun parses_a_bare_array_of_movie_history_entries() {
        val json = """
            [
              {
                "watched_at": "2020-01-01T00:00:00.000Z",
                "type": "movie",
                "movie": { "title": "Poor Things", "year": 2023, "ids": { "imdb": "tt14230458", "tmdb": 792307 } }
              }
            ]
        """.trimIndent()

        val payload = parser.parse(json)

        assertEquals(ImportSource.TRAKT, payload.source)
        val title = payload.titles.single()
        assertEquals(MediaType.MOVIE, title.type)
        assertEquals("tt14230458", title.externalRef.imdbId)
        assertEquals("792307", title.externalRef.tmdbId)
        assertTrue(title.watched)
    }

    @Test
    fun parses_episode_history_entries_and_groups_by_show() {
        val json = """
            [
              {
                "watched_at": "2020-01-01T00:00:00.000Z",
                "type": "episode",
                "episode": { "season": 1, "number": 1, "ids": { "tmdb": 63056 } },
                "show": { "title": "Severance", "ids": { "imdb": "tt11280740", "tmdb": 95396 } }
              },
              {
                "watched_at": "2020-01-02T00:00:00.000Z",
                "type": "episode",
                "episode": { "season": 1, "number": 2, "ids": { "tmdb": 63057 } },
                "show": { "title": "Severance", "ids": { "imdb": "tt11280740", "tmdb": 95396 } }
              }
            ]
        """.trimIndent()

        val payload = parser.parse(json)

        assertEquals(1, payload.titles.size)
        val title = payload.titles.single()
        assertEquals(MediaType.TV, title.type)
        assertEquals(2, title.episodes.size)
    }

    @Test
    fun wrapping_object_combines_history_and_ratings() {
        val json = """
            {
              "history": [
                { "watched_at": "2020-01-01T00:00:00.000Z", "type": "movie",
                  "movie": { "title": "Dune", "ids": { "imdb": "tt1160419" } } }
              ],
              "ratings": [
                { "rated_at": "2020-01-02T00:00:00.000Z", "rating": 9, "type": "movie",
                  "movie": { "title": "Dune", "ids": { "imdb": "tt1160419" } } }
              ]
            }
        """.trimIndent()

        val payload = parser.parse(json)

        val title = payload.titles.single()
        assertTrue(title.watched) // from the history entry
        assertEquals(9, title.rating) // from the ratings entry, merged onto the same title
    }

    @Test
    fun a_pure_rating_entry_with_no_history_does_not_mark_watched() {
        val json = """
            [
              { "rated_at": "2020-01-02T00:00:00.000Z", "rating": 7, "type": "movie",
                "movie": { "title": "Dune", "ids": { "imdb": "tt1160419" } } }
            ]
        """.trimIndent()

        val title = parser.parse(json).titles.single()

        assertEquals(false, title.watched)
        assertEquals(7, title.rating)
    }

    @Test
    fun show_level_rating_adds_the_show_with_no_episode_ticks() {
        val json = """
            [
              { "rated_at": "2020-01-02T00:00:00.000Z", "rating": 10, "type": "show",
                "show": { "title": "Severance", "ids": { "imdb": "tt11280740" } } }
            ]
        """.trimIndent()

        val title = parser.parse(json).titles.single()

        assertEquals(MediaType.TV, title.type)
        assertEquals(10, title.rating)
        assertTrue(title.episodes.isEmpty())
    }

    @Test
    fun malformed_json_yields_an_empty_payload_rather_than_throwing() {
        val payload = parser.parse("{not valid json")

        assertTrue(payload.titles.isEmpty())
    }

    @Test
    fun entry_with_neither_movie_show_nor_episode_is_skipped() {
        val json = """[ { "watched_at": "2020-01-01T00:00:00.000Z", "type": "movie" } ]"""

        val payload = parser.parse(json)

        assertTrue(payload.titles.isEmpty())
        assertEquals(1, payload.skippedRowCount)
    }

    @Test
    fun episode_entry_missing_season_or_number_is_skipped() {
        val json = """
            [
              { "watched_at": "2020-01-01T00:00:00.000Z", "type": "episode",
                "episode": { "number": 1, "ids": {} },
                "show": { "title": "Severance", "ids": {} } }
            ]
        """.trimIndent()

        val payload = parser.parse(json)

        assertTrue(payload.titles.isEmpty())
        assertEquals(1, payload.skippedRowCount)
    }

    @Test
    fun blank_content_yields_an_empty_payload() {
        val payload = parser.parse("")

        assertTrue(payload.titles.isEmpty())
    }
}
