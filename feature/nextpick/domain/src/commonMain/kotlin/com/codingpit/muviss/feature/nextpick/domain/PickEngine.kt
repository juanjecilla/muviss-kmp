package com.codingpit.muviss.feature.nextpick.domain

import com.codingpit.muviss.feature.nextpick.api.PickCriteria
import com.codingpit.muviss.feature.nextpick.api.PickSource
import com.codingpit.muviss.models.GenreKey
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MutedGenres
import com.codingpit.muviss.models.WatchStatus

/** One way of finding candidates: the library's backlog, TMDB's discover, its recommendations. Each decides from [PickCriteria.source] and [PickContext.online] whether it has anything to say. */
fun interface PickCandidates {
    suspend fun gather(criteria: PickCriteria, context: PickContext): List<PickCandidate>
}

/** Titles the person has ruled on or is waiting to see again (Triage's decisions, pending Snoozes), which a pick never repeats. */
fun interface PickExclusions {
    suspend fun excluded(): Set<MediaId>
}

/** The person's own ratings and favorites, read once per session. */
fun interface PickHistoryReader {
    suspend fun read(criteria: PickCriteria): PickHistory
}

/** The standing Muted Genres (ADR 0031), including the session chip that lifts them. */
fun interface PickMuting {
    suspend fun current(): MutedGenres
}

/**
 * What a session ends in. [offline] says the pool is the library alone because there was no
 * connection, so the screen can say so instead of looking like a thin catalogue.
 */
data class PickResult(val picks: List<RankedPick>, val offline: Boolean)

/**
 * Criteria in, ranked Next Picks with reasons out (EPIC 51, ADR 0032). It owns the rules that no
 * source and no ranker should: what a pick may never be, and how the pools merge.
 *
 * - Titles the person has ruled on or snoozed are never offered, and neither is anything already in
 *   progress (that is Watch Next's question).
 * - A title already finished comes back only when a rewatch was said to be fine, and then only from
 *   the library: "something new" never includes a title the library knows.
 * - Muted Genres apply, except that a genre the person picked in this session (through a mood) is
 *   shown even if muted: an explicit choice beats a standing one. Other muted genres still apply
 *   inside it.
 * - The same title from two sources is one candidate, and the library's copy wins because it carries
 *   what the person has said about it.
 *
 * Nothing here reaches the network or the database: sources and readers are handed in.
 */
class PickEngine(
    private val sources: List<PickCandidates>,
    private val exclusions: PickExclusions,
    private val history: PickHistoryReader,
    private val muting: PickMuting,
    private val ranker: PickRanker = RuleBasedRanker(),
) {

    suspend fun pick(criteria: PickCriteria, context: PickContext, limit: Int = DEFAULT_LIMIT): PickResult {
        val excluded = exclusions.excluded()
        val muted = effectiveMuting(criteria, muting.current())
        val pool = sources.flatMap { it.gather(criteria, context) }
            .merged()
            .filter { allowed(it, criteria, excluded, muted) }
        val ranked = ranker.rank(criteria, pool, history.read(criteria)).take(limit)
        return PickResult(ranked, offline = !context.online)
    }

    private fun List<PickCandidate>.merged(): List<PickCandidate> = groupBy { it.id }.values.map { copies ->
        copies.firstOrNull { it.source == CandidateSource.BACKLOG } ?: copies.first()
    }

    private fun allowed(c: PickCandidate, criteria: PickCriteria, excluded: Set<MediaId>, muted: MutedGenres): Boolean {
        if (c.id in excluded || muted.hides(c.summary)) return false
        val inLibrary = c.libraryStatus != null
        return when (c.libraryStatus) {
            null -> criteria.source != PickSource.MY_LIST
            WatchStatus.NOT_STARTED -> criteria.source != PickSource.SOMETHING_NEW
            WatchStatus.WATCHING -> false
            WatchStatus.WATCHED, WatchStatus.FINISHED -> inLibrary && criteria.rewatchOk == true && criteria.source != PickSource.SOMETHING_NEW
        }
    }

    /** [muted] without the genres a mood the person picked reads as, for either type: choosing beats muting. */
    private fun effectiveMuting(criteria: PickCriteria, muted: MutedGenres): MutedGenres {
        if (criteria.moods.isEmpty()) return muted
        val chosen = listOf(com.codingpit.muviss.models.MediaType.MOVIE, com.codingpit.muviss.models.MediaType.TV).flatMap { type ->
            MoodGenres.genreIds(criteria.moods, type).map { GenreKey(type, it) }
        }.toSet()
        return MutedGenres(muted.keys - chosen)
    }

    companion object {
        const val DEFAULT_LIMIT = 5
    }
}
