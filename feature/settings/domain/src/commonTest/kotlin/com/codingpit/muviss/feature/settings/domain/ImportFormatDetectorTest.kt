package com.codingpit.muviss.feature.settings.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImportFormatDetectorTest {

    @Test
    fun json_array_is_trakt() {
        assertEquals(ImportSource.TRAKT, ImportFormatDetector.detect("[ { \"type\": \"movie\" } ]"))
    }

    @Test
    fun json_object_is_trakt() {
        assertEquals(ImportSource.TRAKT, ImportFormatDetector.detect("  \n {\"history\": []}"))
    }

    @Test
    fun csv_with_title_and_type_columns_is_generic() {
        assertEquals(ImportSource.GENERIC_CSV, ImportFormatDetector.detect("title,type,rating\nDune,movie,9\n"))
    }

    @Test
    fun csv_with_season_number_column_is_tv_time() {
        assertEquals(ImportSource.TV_TIME, ImportFormatDetector.detect("series_name,season_number,episode_number\nSeverance,1,1\n"))
    }

    @Test
    fun csv_with_movie_name_column_is_tv_time() {
        assertEquals(ImportSource.TV_TIME, ImportFormatDetector.detect("movie_name,tmdb_id\nDune,438631\n"))
    }

    @Test
    fun unrecognized_header_is_null() {
        assertNull(ImportFormatDetector.detect("foo,bar,baz\n1,2,3\n"))
    }

    @Test
    fun blank_content_is_null() {
        assertNull(ImportFormatDetector.detect(""))
    }
}
