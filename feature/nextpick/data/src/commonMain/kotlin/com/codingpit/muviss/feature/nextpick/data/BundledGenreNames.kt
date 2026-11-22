package com.codingpit.muviss.feature.nextpick.data

import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaType

/**
 * TMDB's genre names and ids for the two UI languages (en-US, es-ES), bundled so a library title can be
 * mapped back to ids with no network, cold start included (#547). Library snapshots store names, not ids.
 *
 * These are a fixed snapshot of TMDB's genre lists, not a live catalogue: a language outside en and es
 * matches only through the live catalogue, and a name TMDB has renamed since this was written degrades
 * to no id. TMDB numbers movie and TV genres separately, so each type has its own list.
 */
internal object BundledGenreNames {

    /** The en-US and es-ES names for [type], en first, so an English match wins a tie. */
    fun genres(type: MediaType): List<Genre> = when (type) {
        MediaType.MOVIE -> MOVIE_EN + MOVIE_ES
        MediaType.TV -> TV_EN + TV_ES
    }

    private val MOVIE_EN = listOf(
        genre("28", "Action"), genre("12", "Adventure"), genre("16", "Animation"), genre("35", "Comedy"),
        genre("80", "Crime"), genre("99", "Documentary"), genre("18", "Drama"), genre("10751", "Family"),
        genre("14", "Fantasy"), genre("36", "History"), genre("27", "Horror"), genre("10402", "Music"),
        genre("9648", "Mystery"), genre("10749", "Romance"), genre("878", "Science Fiction"),
        genre("10770", "TV Movie"), genre("53", "Thriller"), genre("10752", "War"), genre("37", "Western"),
    )

    private val MOVIE_ES = listOf(
        genre("28", "Acción"), genre("12", "Aventura"), genre("16", "Animación"), genre("35", "Comedia"),
        genre("80", "Crimen"), genre("99", "Documental"), genre("18", "Drama"), genre("10751", "Familia"),
        genre("14", "Fantasía"), genre("36", "Historia"), genre("27", "Terror"), genre("10402", "Música"),
        genre("9648", "Misterio"), genre("10749", "Romance"), genre("878", "Ciencia ficción"),
        genre("10770", "Película de TV"), genre("53", "Suspense"), genre("10752", "Bélica"), genre("37", "Western"),
    )

    private val TV_EN = listOf(
        genre("10759", "Action & Adventure"), genre("16", "Animation"), genre("35", "Comedy"), genre("80", "Crime"),
        genre("99", "Documentary"), genre("18", "Drama"), genre("10751", "Family"), genre("10762", "Kids"),
        genre("9648", "Mystery"), genre("10763", "News"), genre("10764", "Reality"),
        genre("10765", "Sci-Fi & Fantasy"), genre("10766", "Soap"), genre("10767", "Talk"),
        genre("10768", "War & Politics"), genre("37", "Western"),
    )

    private val TV_ES = listOf(
        genre("10759", "Acción y Aventura"), genre("16", "Animación"), genre("35", "Comedia"), genre("80", "Crimen"),
        genre("99", "Documental"), genre("18", "Drama"), genre("10751", "Familia"), genre("10762", "Infantil"),
        genre("9648", "Misterio"), genre("10763", "Noticias"), genre("10764", "Reality"),
        genre("10765", "Sci-Fi y Fantasía"), genre("10766", "Telenovela"), genre("10767", "Talk"),
        genre("10768", "Guerra y política"), genre("37", "Western"),
    )

    private fun genre(id: String, name: String) = Genre(id = id, name = name)
}
