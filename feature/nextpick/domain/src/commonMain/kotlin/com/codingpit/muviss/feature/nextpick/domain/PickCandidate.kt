package com.codingpit.muviss.feature.nextpick.domain

import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.WatchStatus

/** Where a candidate came from. The ranker prefers none of them by itself: [PickCriteria.source] already narrowed the pool. */
enum class CandidateSource {
    /** A title in the library that is waiting to be watched. Works offline. */
    BACKLOG,

    /** `/discover` with the session's filters. */
    DISCOVER,

    /** TMDB's recommendations for the anchor or a seed title. */
    RECOMMENDED,

    /** TMDB's similar titles for the anchor or a seed title. */
    SIMILAR,
}

/**
 * A title the engine may offer, with the facts the ranker and the filters need. Plain data so the
 * ranker is testable with no network and no database (ADR 0032): sources fill it in, nothing here reads.
 */
data class PickCandidate(
    val summary: MediaSummary,
    val source: CandidateSource,
    /** The library's status for it, or null when it is not in the library. */
    val libraryStatus: WatchStatus? = null,
    val runtimeMinutes: Int? = null,
    val originalLanguage: String? = null,
    val favorite: Boolean = false,
    /** The person's own rating, 1 to 10, when they gave one. */
    val userRating: Int? = null,
    val addedAtEpochMs: Long? = null,
) {
    val id: MediaId get() = summary.id
}

/**
 * What the person's own history says, read once per session. Derived from ratings and favorites the
 * person gave on purpose; nothing here is learned from what they accepted or refused in a Pick Session
 * (ADR 0032: no implicit learning).
 */
data class PickHistory(
    /** Genre id to how the person rates titles with it, as an average on a 1 to 10 scale. */
    val genreRatings: Map<String, Double> = emptyMap(),
    /** Genres of the anchor title, when there is one, so "more like this" can be measured. */
    val anchorGenreIds: Set<String> = emptySet(),
    val anchorTitle: String? = null,
    /** Today's epoch day, for "new" and "classic". */
    val todayEpochDay: Long = 0,
)

/** Why a pick was chosen, as data. The UI turns each into a localized sentence (#400); the domain does not speak a language. */
sealed interface PickReason {
    data class FitsMood(val mood: com.codingpit.muviss.feature.nextpick.api.PickMood) : PickReason
    data class LikeAnchor(val anchorTitle: String?) : PickReason
    data class UnlikeAnchor(val anchorTitle: String?) : PickReason
    data class YouRateTheseGenresHigh(val genreId: String, val average: Double) : PickReason
    data object YourFavorite : PickReason
    data object LongInYourList : PickReason
    data class HighlyRated(val average: Double) : PickReason
    data class FitsTime(val runtimeMinutes: Int) : PickReason
    data object RecentRelease : PickReason
    data object Classic : PickReason
    data class InYourLanguage(val language: String) : PickReason

    /** Nothing stood out: it passed the filters. Better than inventing a reason. */
    data object FitsWhatYouAsked : PickReason
}

/** A [candidate] with its score and the strongest reason it scored. */
data class RankedPick(val candidate: PickCandidate, val score: Double, val reason: PickReason)
