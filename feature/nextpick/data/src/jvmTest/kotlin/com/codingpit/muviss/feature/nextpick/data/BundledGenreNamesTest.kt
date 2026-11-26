package com.codingpit.muviss.feature.nextpick.data

import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [BundledGenreNames] to TMDB's genre lists. The resources under `tmdb-genres` are the raw
 * `/genre/{movie,tv}/list` responses, fetched live in en-US and es-ES on 2026-11-26. TMDB can rename a
 * genre, so a future change means refetching these files and updating the table to match, not editing one
 * side alone. The live API needs a key, so CI reads the checked-in copies.
 */
class BundledGenreNamesTest {

    @Test
    fun movie_names_and_ids_match_tmdb_in_en_then_es() {
        assertEquals(
            listOf("movie-en-US", "movie-es-ES").flatMap { liveGenres(it) },
            BundledGenreNames.genres(MediaType.MOVIE).pairs(),
        )
    }

    @Test
    fun tv_names_and_ids_match_tmdb_in_en_then_es() {
        assertEquals(
            listOf("tv-en-US", "tv-es-ES").flatMap { liveGenres(it) },
            BundledGenreNames.genres(MediaType.TV).pairs(),
        )
    }

    private fun List<Genre>.pairs(): List<Pair<String, String>> = map { it.id to it.name }

    /** Parses `{"genres":[{"id":28,"name":"Action"},...]}` without a JSON dependency; ids are plain numbers. */
    private fun liveGenres(file: String): List<Pair<String, String>> {
        val text = checkNotNull(javaClass.classLoader.getResourceAsStream("tmdb-genres/$file.json")) {
            "missing fixture $file"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return GENRE.findAll(text).map { it.groupValues[1] to it.groupValues[2] }.toList()
    }

    private companion object {
        val GENRE = Regex("""\{"id":(\d+),"name":"([^"]+)"\}""")
    }
}
