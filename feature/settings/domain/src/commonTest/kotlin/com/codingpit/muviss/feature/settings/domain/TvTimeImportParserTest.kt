package com.codingpit.muviss.feature.settings.domain

import com.codingpit.muviss.models.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TvTimeImportParserTest {

    private val parser = TvTimeImportParser()

    @Test
    fun parses_episodes_file_grouping_by_show() {
        val csv = "series_name,tmdb_id,imdb_id,season_number,episode_number,watched_at\n" +
            "Severance,95396,tt11280740,1,1,2022-02-18\n" +
            "Severance,95396,tt11280740,1,2,2022-02-25\n"

        val payload = parser.parse(csv)

        assertEquals(ImportSource.TV_TIME, payload.source)
        assertEquals(1, payload.titles.size)
        val title = payload.titles.single()
        assertEquals(MediaType.TV, title.type)
        assertEquals("95396", title.externalRef.tmdbId)
        assertEquals(2, title.episodes.size)
    }

    @Test
    fun accepts_show_name_and_show_tmdb_id_column_aliases() {
        val csv = "show_name,show_tmdb_id,season_number,episode_number\nSeverance,95396,1,1\n"

        val title = parser.parse(csv).titles.single()

        assertEquals("95396", title.externalRef.tmdbId)
        assertEquals("Severance", title.displayTitle)
    }

    @Test
    fun episode_row_missing_season_or_episode_is_skipped() {
        val csv = "series_name,season_number,episode_number\nSeverance,1,\nSeverance,1,2\n"

        val payload = parser.parse(csv)

        assertEquals(1, payload.titles.single().episodes.size)
        assertEquals(1, payload.skippedRowCount)
    }

    @Test
    fun parses_movies_file_as_watched_with_rating() {
        val csv = "movie_name,tmdb_id,imdb_id,rating,watched_at\nPoor Things,792307,tt14230458,8,2024-03-01\n"

        val payload = parser.parse(csv)

        val title = payload.titles.single()
        assertEquals(MediaType.MOVIE, title.type)
        assertTrue(title.watched)
        assertEquals(8, title.rating)
    }

    @Test
    fun movies_file_accepts_name_column_alias() {
        val csv = "name,tmdb_id\nPoor Things,792307\n"

        val title = parser.parse(csv).titles.single()

        assertEquals("Poor Things", title.displayTitle)
    }

    @Test
    fun movie_row_missing_name_is_skipped() {
        val csv = "name,tmdb_id\n,792307\nDune,438631\n"

        val payload = parser.parse(csv)

        assertEquals(1, payload.titles.size)
        assertEquals(1, payload.skippedRowCount)
    }

    @Test
    fun empty_content_yields_an_empty_payload() {
        val payload = parser.parse("")

        assertTrue(payload.titles.isEmpty())
    }
}
