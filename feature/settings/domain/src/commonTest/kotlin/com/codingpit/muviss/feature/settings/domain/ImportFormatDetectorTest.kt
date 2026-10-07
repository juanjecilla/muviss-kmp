package com.codingpit.muviss.feature.settings.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun a_muviss_backup_is_recognised_and_not_read_as_trakt() {
        val backup = """{ "formatVersion": 2, "exportedAtEpochMs": 1, "collection": [] }"""
        assertTrue(ImportFormatDetector.isMuvissBackup(backup))
        assertEquals(null, ImportFormatDetector.detect(backup))
    }

    @Test
    fun a_v1_backup_with_no_version_is_recognised_too() {
        assertTrue(ImportFormatDetector.isMuvissBackup("""{"exportedAtEpochMs":1,"collection":[],"progress":[]}"""))
    }

    @Test
    fun a_trakt_export_is_not_a_backup() {
        assertFalse(ImportFormatDetector.isMuvissBackup("""[ { "type": "movie" } ]"""))
        assertFalse(ImportFormatDetector.isMuvissBackup("""{"history": []}"""))
    }
}
