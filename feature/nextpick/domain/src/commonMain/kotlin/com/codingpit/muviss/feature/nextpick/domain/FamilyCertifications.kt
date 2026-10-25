package com.codingpit.muviss.feature.nextpick.domain

import com.codingpit.muviss.models.MediaType

/** A TMDB certification filter: the country whose ratings apply and the ratings that pass. */
data class CertificationFilter(val country: String, val allowed: Set<String>)

/**
 * "Watching with the family" as a certification filter (ADR 0032).
 *
 * Ratings are national, so the filter follows the device's region. The app speaks English and Spanish,
 * so those two regions have a table and every other region falls back to the US one: a wrong guess
 * there is a filter that is too loose or too strict for that country's ratings, never a crash, and a
 * region that matters can get its own row. Alone and with a partner add no filter.
 */
object FamilyCertifications {
    private const val FALLBACK = "US"

    // The values are TMDB's own (`/certification/{movie,tv}/list`), not the agencies' labels: a value
    // TMDB never stores matches nothing and silently narrows the filter.
    private val movies = mapOf(
        "US" to setOf("G", "PG"),
        // ICAA: A/Ai (all ages, Ai "recommended for children"), 7/7i. Spanish films carry no "TP".
        "ES" to setOf("A", "Ai", "7", "7i"),
    )

    private val shows = mapOf(
        "US" to setOf("TV-Y", "TV-Y7", "TV-G", "TV-PG"),
        // Spain's TV scale differs from its films': ERI (for younger children), TP (all ages), 7.
        "ES" to setOf("ERI", "TP", "7"),
    )

    /** The filter for [region] (an ISO 3166-1 alpha-2 code, any case) and [type]. */
    fun forRegion(region: String?, type: MediaType): CertificationFilter {
        val country = region?.uppercase()?.takeIf { it in movies } ?: FALLBACK
        val table = if (type == MediaType.MOVIE) movies else shows
        return CertificationFilter(country, table.getValue(country))
    }
}
