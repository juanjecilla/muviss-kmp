package com.codingpit.muviss.feature.nextpick.domain

import com.codingpit.muviss.feature.nextpick.api.PickCriteria
import com.codingpit.muviss.feature.nextpick.api.PickSource
import com.codingpit.muviss.models.GenreKey
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MutedGenres
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
 * - The ranker's output is enforced here, not trusted (ADR 0032): only picks for titles in the pool
 *   are kept, each id once, and a ranker that throws, times out or returns nothing usable is replaced
 *   by [RuleBasedRanker]'s result. Nothing about that fallback is shown to the person.
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

    private val fallback = RuleBasedRanker()

    suspend fun pick(criteria: PickCriteria, context: PickContext, limit: Int = DEFAULT_LIMIT): PickResult {
        val excluded = exclusions.excluded()
        val muted = effectiveMuting(criteria, muting.current())
        val pool = sources.flatMap { it.gather(criteria, context) }
            .merged()
            .filter { allowed(it, criteria, excluded, muted) }
        val ownHistory = history.read(criteria)
        val ranked = rankOrFallBack(criteria, pool, ownHistory, excluded).take(limit)
        return PickResult(ranked, offline = !context.online)
    }

    /**
     * The ranker's picks, enforced against [pool]. When it fails, times out, or leaves nothing usable,
     * the rules' order over the same pool. A cancellation of the caller is rethrown, never swallowed.
     */
    private suspend fun rankOrFallBack(
        criteria: PickCriteria,
        pool: List<PickCandidate>,
        history: PickHistory,
        excluded: Set<MediaId>,
    ): List<RankedPick> {
        val enforced = attempt(criteria, pool, history)?.let { enforce(it, pool, excluded) }
        return enforced?.takeIf { it.isNotEmpty() } ?: fallback.rank(criteria, pool, history)
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Any ranker failure means "use the rules", never an error on screen.
    private suspend fun attempt(criteria: PickCriteria, pool: List<PickCandidate>, history: PickHistory): List<RankedPick>? = try {
        withTimeoutOrNull(RANKER_TIMEOUT) { ranker.rank(criteria, pool, history) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Keeps only picks for titles in [pool] and not in [excluded], each id once, using the pool's own copy of the candidate. */
    private fun enforce(ranked: List<RankedPick>, pool: List<PickCandidate>, excluded: Set<MediaId>): List<RankedPick> {
        val byId = pool.associateBy { it.id }
        return ranked
            .mapNotNull { pick -> byId[pick.candidate.id]?.takeIf { it.id !in excluded }?.let { RankedPick(it, pick.score, pick.reason) } }
            .distinctBy { it.candidate.id }
    }

    /**
     * One copy per title. The library's copy wins (it carries what the person said about it), then a copy
     * TMDB offered because of the anchor, which is what [RuleBasedRanker] credits; `/discover` is last.
     */
    private fun List<PickCandidate>.merged(): List<PickCandidate> = groupBy { it.id }.values.map { copies ->
        copies.minBy { sourcePriority(it.source) }
    }

    private fun sourcePriority(source: CandidateSource): Int = when (source) {
        CandidateSource.BACKLOG -> 0
        CandidateSource.RECOMMENDED, CandidateSource.SIMILAR -> 1
        CandidateSource.DISCOVER -> 2
    }

    private fun allowed(c: PickCandidate, criteria: PickCriteria, excluded: Set<MediaId>, muted: MutedGenres): Boolean {
        // The title the person just finished is never the answer to "more like it" or "something else".
        if (c.id == criteria.anchor?.mediaId) return false
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

        /** How long a ranker may take before the rules answer instead. A model call that is slower than this is not worth the wait. */
        private val RANKER_TIMEOUT: Duration = 5.seconds
    }
}
