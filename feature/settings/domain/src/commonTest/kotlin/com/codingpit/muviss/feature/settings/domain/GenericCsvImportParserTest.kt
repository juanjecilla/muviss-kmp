package com.codingpit.muviss.feature.settings.domain

import com.codingpit.muviss.models.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GenericCsvImportParserTest {

    private val parser = GenericCsvImportParser()

    @Test
    fun parses_a_title_level_movie_row_as_watched() {
        val csv = "title,type,imdb_id,tmdb_id,rating,watched\n" +
            "Poor Things,movie,tt14230458,792307,8,true\n"

        val payload = parser.parse(csv)

        assertEquals(1, payload.titles.size)
        val title = payload.titles.single()
        assertEquals(MediaType.MOVIE, title.type)
        assertEquals("tt14230458", title.externalRef.imdbId)
        assertEquals("792307", title.externalRef.tmdbId)
        assertEquals(8, title.rating)
        assertTrue(title.watched)
        assertEquals(0, payload.skippedRowCount)
    }

    @Test
    fun unwatched_movie_row_does_not_set_the_watched_flag() {
        val csv = "title,type,watched\nDune,movie,false\n"

        val title = parser.parse(csv).titles.single()

        assertEquals(false, title.watched)
    }

    @Test
    fun groups_episode_rows_for_the_same_show_by_id() {
        val csv = "title,type,imdb_id,season,episode\n" +
            "The Bear,tv,tt14452776,1,1\n" +
            "The Bear,tv,tt14452776,1,2\n"

        val payload = parser.parse(csv)

        assertEquals(1, payload.titles.size)
        val title = payload.titles.single()
        assertEquals(2, title.episodes.size)
        assertEquals(setOf(ImportedEpisode(1, 1), ImportedEpisode(1, 2)), title.episodes.toSet())
    }

    @Test
    fun a_title_level_tv_row_adds_the_show_without_episode_ticks() {
        val csv = "title,type\nThe Bear,tv\n"

        val title = parser.parse(csv).titles.single()

        assertEquals(MediaType.TV, title.type)
        assertTrue(title.episodes.isEmpty())
        assertEquals(false, title.watched)
    }

    @Test
    fun row_missing_title_is_skipped_and_counted() {
        val csv = "title,type\n,movie\nDune,movie\n"

        val payload = parser.parse(csv)

        assertEquals(1, payload.titles.size)
        assertEquals(1, payload.skippedRowCount)
    }

    @Test
    fun row_with_unknown_type_is_skipped() {
        val csv = "title,type\nDune,documentary\n"

        val payload = parser.parse(csv)

        assertTrue(payload.titles.isEmpty())
        assertEquals(1, payload.skippedRowCount)
    }

    @Test
    fun row_with_no_ids_still_parses_but_is_unresolvable_later() {
        val csv = "title,type\nSome Obscure Short,movie\n"

        val title = parser.parse(csv).titles.single()

        assertTrue(title.externalRef.isEmpty)
    }

    @Test
    fun empty_content_yields_an_empty_payload() {
        val payload = parser.parse("")

        assertTrue(payload.titles.isEmpty())
        assertEquals(0, payload.skippedRowCount)
    }

    @Test
    fun quoted_field_with_embedded_comma_parses_as_one_title() {
        val csv = "title,type\n\"Everything, Everywhere\",movie\n"

        val title = parser.parse(csv).titles.single()

        assertEquals("Everything, Everywhere", title.displayTitle)
        assertNull(title.externalRef.imdbId)
    }
}
