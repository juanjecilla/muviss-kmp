package com.codingpit.muviss.models

import kotlinx.serialization.Serializable

/** Lightweight media representation for search results and lists. */
@Serializable
data class MediaSummary(
    val id: MediaId,
    val title: String,
    val year: Int? = null,
    val posterUrl: String? = null,
    val overview: String? = null,
    val rating: Double? = null,
    /** The source's genre ids (TMDB's, as strings); empty when the source did not say. Keyed by [type]. */
    val genreIds: List<String> = emptyList(),
) {
    val type: MediaType get() = id.type
}

/**
 * Production lifecycle of a title. For TV this distinguishes an ongoing show
 * (more episodes may come) from one that has ended — the difference between
 * [WatchStatus.WATCHED] and [WatchStatus.FINISHED].
 */
@Serializable
enum class ProductionStatus {
    RELEASED, // movies
    RETURNING, // TV, ongoing
    ENDED, // TV, complete
    CANCELED, // TV, complete
    UNKNOWN,
    ;

    val isFinishedProduction: Boolean get() = this == ENDED || this == CANCELED || this == RELEASED
}

/** Full detail for a title, including seasons/episodes for TV. */
@Serializable
data class MediaDetails(
    val summary: MediaSummary,
    val anchors: MediaAnchors = MediaAnchors(),
    val genres: List<String> = emptyList(),
    val runtimeMinutes: Int? = null,
    val productionStatus: ProductionStatus = ProductionStatus.UNKNOWN,
    val seasons: List<Season> = emptyList(),
    /** Wide (16:9) backdrop art for the detail hero; null when TMDB has none. */
    val backdropUrl: String? = null,
    /** Who is in it, in the source's billing order. Empty when the source lists nobody. */
    val cast: List<Credit> = emptyList(),
    /** The whole crew (directors, creators, writers...), see [Credit.job]. Empty when the source lists nobody. */
    val crew: List<Credit> = emptyList(),
) {
    val id: MediaId get() = summary.id
    val type: MediaType get() = summary.type
}

@Serializable
data class Season(
    val number: Int,
    val name: String,
    val episodes: List<Episode>,
)

@Serializable
data class Episode(
    val id: EpisodeId,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val name: String,
    val airDateEpochDay: Long? = null,
    val stillUrl: String? = null,
    /** Minutes, when TMDB reports it for this episode; null otherwise (see profile's hours-watched fallback). */
    val runtimeMinutes: Int? = null,
)

/**
 * Everything worth showing about one episode, beyond the list-row essentials
 * [Episode] carries.
 *
 * Separate from [Episode] because a season's episode list is fetched for every
 * show the app touches, and carrying overviews, cast and crew for a hundred
 * episodes would bloat every one of those responses for data only ever read
 * one episode at a time.
 */
@Serializable
data class EpisodeDetails(
    val id: EpisodeId,
    val name: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val overview: String? = null,
    val airDateEpochDay: Long? = null,
    val stillUrl: String? = null,
    val runtimeMinutes: Int? = null,
    /** The source's public average (0-10), not the user's own rating. */
    val voteAverage: Double? = null,
    val guestStars: List<EpisodeCredit> = emptyList(),
    val crew: List<EpisodeCredit> = emptyList(),
)

/** One person on an episode: a guest star (with their character) or a crew member (with their job). */
@Serializable
data class EpisodeCredit(
    val name: String,
    /** Null when the source did not say who this is, so the credit cannot open a Person. */
    val personId: PersonId? = null,
    /** Character played, for a guest star. */
    val character: String? = null,
    /** Job done, for a crew member (e.g. "Director"). */
    val job: String? = null,
    val profileUrl: String? = null,
)

/** Stable episode identity within a show, e.g. `tmdb:tv:1399/1/1`. */
@Serializable
data class EpisodeId(
    val show: MediaId,
    val seasonNumber: Int,
    val episodeNumber: Int,
) {
    override fun toString(): String = "$show/$seasonNumber/$episodeNumber"

    companion object {
        /**
         * Synthetic id for a movie's single watched tick. Movies have no
         * season/episode structure, but progress is still stored per-"episode"
         * (see `EpisodeProgress.sq`), so this convention gives every movie one
         * stable id to tick. Safe from collision with any real TV episode id
         * because [show] already carries the media's [MediaType].
         */
        fun forMovie(show: MediaId) = EpisodeId(show, seasonNumber = 0, episodeNumber = 0)

        /**
         * Reads back what [toString] wrote, e.g. `tmdb:tv:1399/3/9`. Needed
         * because `episodePlay` (ADR 0011) keys its rows by that string and
         * has no season/episode columns of its own to rebuild from.
         */
        fun parse(raw: String): EpisodeId {
            // Splitting rather than scanning back from the last separator:
            // a MediaId contains no '/', so there are always exactly three
            // parts. It also mirrors MediaId.parse, and sidesteps a
            // Kotlin/Wasm compiler crash on `lastIndexOf(Char, Int)`.
            val parts = raw.split('/')
            require(parts.size == 3) { "Malformed EpisodeId: $raw" }
            val season = parts[1].toIntOrNull()
            val episode = parts[2].toIntOrNull()
            require(season != null && episode != null) { "Malformed EpisodeId: $raw" }
            return EpisodeId(MediaId.parse(parts[0]), season, episode)
        }
    }
}

/**
 * One [Person]'s part in one title: the character they played or the job they
 * did. A person with several roles in the same title has one Credit per role
 * group the source returns, never merged here.
 */
@Serializable
data class Credit(
    val personId: PersonId,
    val name: String,
    val profileUrl: String? = null,
    /** Character played, for cast. */
    val character: String? = null,
    /** Job done, for crew (e.g. "Director"). */
    val job: String? = null,
    /** The crew department (e.g. "Directing"); null for cast. */
    val department: String? = null,
    /** Episodes this credit covers, for a show's whole-run credits; null for a movie. */
    val episodeCount: Int? = null,
) {
    companion object {
        /** The job and department a show's creators are given: the source lists them outside its crew. */
        const val CREATOR_JOB = "Creator"
        const val CREATOR_DEPARTMENT = "Creator"
    }
}

/** What the source says about one person: who they are, and every title they are credited on. */
@Serializable
data class PersonDetails(
    val id: PersonId,
    val name: String,
    val profileUrl: String? = null,
    /** In the app's language when the source has it, otherwise in English, otherwise null. */
    val biography: String? = null,
    val birthday: String? = null,
    val deathday: String? = null,
    val placeOfBirth: String? = null,
    /** The source's main department for them ("Acting", "Directing"...), the tab that opens first. */
    val knownForDepartment: String? = null,
    val anchors: PersonAnchors = PersonAnchors(),
    /** Their credits as the source lists them: one entry per role, a title may repeat. */
    val credits: List<PersonCredit> = emptyList(),
)

/** Cross-source ids of a person, for links out. */
@Serializable
data class PersonAnchors(
    val imdbId: String? = null,
    val wikidataId: String? = null,
)

/** One role a person had in one title. */
@Serializable
data class PersonCredit(
    val title: MediaSummary,
    /** Character played, for an acting credit. */
    val character: String? = null,
    /** Job done, for a crew credit. */
    val job: String? = null,
    /** True for an acting credit, false for a crew one. */
    val acting: Boolean,
    /** Release or first-air date as an epoch day; null when the source has none. */
    val releaseEpochDay: Long? = null,
    /** How many people voted on the title, to tell a famous credit from an obscure one. */
    val voteCount: Int = 0,
    /** Episodes the person appears in, for a show credit that says so. */
    val episodeCount: Int? = null,
)
