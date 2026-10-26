package com.codingpit.muviss.feature.nextpick.domain

import com.codingpit.muviss.feature.nextpick.api.PickMood
import com.codingpit.muviss.models.MediaType

/**
 * Mood is not a feeling to TMDB, it is a set of genres (ADR 0032: quality is capped by what TMDB filters
 * can express). This table is the one place that mapping lives, as data, so it can be read, tested and
 * changed without hunting through screens.
 *
 * Genre ids are TMDB's, as strings like everywhere else in the app. Movies and shows have different
 * genre lists: TMDB folds action and adventure into one TV genre, sci-fi and fantasy into another, and
 * has no TV genre for thrillers, horror or romance, so those moods lean on the nearest ones (a TV
 * "tense" is crime and mystery; a TV "romantic" is drama and comedy). A better TV answer needs keyword
 * filters, which is the engine's to add (#399), not this table's.
 */
object MoodGenres {

    private val movie: Map<PickMood, Set<String>> = mapOf(
        PickMood.LIGHT to setOf("35", "10751", "16"), // comedy, family, animation
        PickMood.TENSE to setOf("53", "80", "9648", "27"), // thriller, crime, mystery, horror
        PickMood.THOUGHTFUL to setOf("18", "99", "36"), // drama, documentary, history
        PickMood.ESCAPE to setOf("12", "878", "14", "28"), // adventure, sci-fi, fantasy, action
        PickMood.ROMANTIC to setOf("10749", "35"), // romance, comedy
    )

    private val tv: Map<PickMood, Set<String>> = mapOf(
        PickMood.LIGHT to setOf("35", "10751", "16"), // comedy, family, animation
        PickMood.TENSE to setOf("80", "9648"), // crime, mystery
        PickMood.THOUGHTFUL to setOf("18", "99"), // drama, documentary
        PickMood.ESCAPE to setOf("10759", "10765"), // action & adventure, sci-fi & fantasy
        PickMood.ROMANTIC to setOf("18", "35"), // drama, comedy: TMDB has no TV romance genre
    )

    /**
     * The genre ids to look in for [moods] and [type], united. A null [type] (movies or shows) is the
     * union of both lists. No moods means no constraint, not "no genres".
     */
    fun genreIds(moods: Set<PickMood>, type: MediaType?): Set<String> {
        if (moods.isEmpty()) return emptySet()
        val tables = when (type) {
            MediaType.MOVIE -> listOf(movie)
            MediaType.TV -> listOf(tv)
            null -> listOf(movie, tv)
        }
        return tables.flatMap { table -> moods.flatMap { table.getValue(it) } }.toSet()
    }
}
