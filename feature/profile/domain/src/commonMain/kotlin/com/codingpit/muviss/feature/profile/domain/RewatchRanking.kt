package com.codingpit.muviss.feature.profile.domain

import com.codingpit.muviss.core.common.civilDateOf
import com.codingpit.muviss.core.common.epochDayOf
import com.codingpit.muviss.core.common.epochDayOfCivil
import com.codingpit.muviss.core.common.epochMsAtStartOfDay
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType

/**
 * Which slice of history a rewatch ranking covers.
 *
 * The bound this produces applies to the rewatch itself and never to the
 * viewing that came before it (ADR 0012), so switching to [THIS_YEAR] narrows
 * *when you rewatched*, not *what counts as a rewatch*.
 */
enum class RewatchWindow {
    ALL_TIME,
    THIS_YEAR,
    ;

    /** The `sinceEpochMs` bound to hand to `ProgressApi.observeRewatchCounts`. */
    fun sinceEpochMs(nowEpochMs: Long): Long = when (this) {
        ALL_TIME -> 0L
        THIS_YEAR -> epochMsAtStartOfDay(epochDayOfCivil(civilDateOf(epochDayOf(nowEpochMs)).year, month = 1, day = 1))
    }
}

/**
 * One title's place in a rewatch ranking.
 *
 * [rewatches] counts *viewings beyond the first*, per element: for a show
 * that is episode rewatches, for a film it is viewings after the first. A
 * first watch-through therefore scores zero however long the title is, which
 * is what keeps a 200-episode show from outranking everything simply by being
 * long. The two are never mixed into one comparison without their unit shown
 * alongside — see the UI's per-row labelling and ADR 0012.
 */
data class RewatchEntry(
    val mediaId: MediaId,
    val title: String,
    val posterUrl: String?,
    val mediaType: MediaType,
    val rewatches: Int,
)

/** Shows and movies ranked separately, each already sorted, most rewatched first. */
data class RewatchRanking(
    val shows: List<RewatchEntry> = emptyList(),
    val movies: List<RewatchEntry> = emptyList(),
) {
    val isEmpty: Boolean get() = shows.isEmpty() && movies.isEmpty()

    /**
     * The [limit] highest-scoring entries across both lists — the profile
     * card's teaser. Mixing types is safe only because every row prints its
     * own unit; the full screen keeps them apart.
     */
    fun top(limit: Int): List<RewatchEntry> = (shows + movies)
        .sortedWith(compareByDescending<RewatchEntry> { it.rewatches }.thenBy { it.title })
        .take(limit)
}

/** One month's rewatch total, for the trend chart. [month] is 1-12. */
data class MonthlyRewatches(
    val year: Int,
    val month: Int,
    val rewatches: Int,
)

/**
 * Joins rewatch counts (owned by the progress feature, which knows nothing of
 * titles) onto the saved library (owned by collection). Pure and total, so it
 * is unit-testable with no database — the same shape as
 * [WatchStreakCalculator].
 *
 * Titles with no [CollectionSummary] are dropped: the ranking is a view of
 * the library sorted by rewatches, and a title removed from the library is
 * not in it. Its plays are untouched on disk and its number returns intact if
 * it is saved again (ADR 0012).
 */
object RewatchRankingCalculator {
    fun calculate(
        rewatchCounts: Map<MediaId, Int>,
        summaries: List<CollectionSummary>,
    ): RewatchRanking {
        val ranked = summaries
            .mapNotNull { summary ->
                val rewatches = rewatchCounts[summary.mediaId]?.takeIf { it > 0 } ?: return@mapNotNull null
                RewatchEntry(
                    mediaId = summary.mediaId,
                    title = summary.title,
                    posterUrl = summary.posterUrl,
                    mediaType = summary.mediaId.type,
                    rewatches = rewatches,
                )
            }
            .sortedWith(compareByDescending<RewatchEntry> { it.rewatches }.thenBy { it.title })
        return RewatchRanking(
            shows = ranked.filter { it.mediaType == MediaType.TV },
            movies = ranked.filter { it.mediaType == MediaType.MOVIE },
        )
    }
}

/**
 * Buckets rewatch timestamps into the trailing [MONTHS_SHOWN] months, oldest
 * first, always emitting every month including the empty ones so the chart
 * has a fixed width and a month with nothing in it reads as a gap rather than
 * vanishing.
 *
 * Months are UTC, derived through [civilDateOf] from the same epoch-day
 * convention [WatchStreakCalculator] uses, rather than through SQLite's date
 * functions — one notion of "what day is it" for the whole app.
 */
object MonthlyRewatchCalculator {
    /** A full year of context, and the reason the chart's window is fixed while the ranking's is not (ADR 0012). */
    const val MONTHS_SHOWN = 12

    /** The `sinceEpochMs` bound covering exactly the months [calculate] will show. */
    fun sinceEpochMs(todayEpochDay: Long): Long {
        val today = civilDateOf(todayEpochDay)
        val firstShown = absoluteMonthOf(today.year, today.month) - (MONTHS_SHOWN - 1)
        return epochMsAtStartOfDay(epochDayOfCivil(yearOf(firstShown), monthOf(firstShown), day = 1))
    }

    fun calculate(rewatchTimestamps: List<Long>, todayEpochDay: Long): List<MonthlyRewatches> {
        val today = civilDateOf(todayEpochDay)
        val currentMonth = absoluteMonthOf(today.year, today.month)
        val counts = rewatchTimestamps
            .map { civilDateOf(epochDayOf(it)) }
            .groupingBy { absoluteMonthOf(it.year, it.month) }
            .eachCount()
        return (currentMonth - (MONTHS_SHOWN - 1)..currentMonth).map { absolute ->
            MonthlyRewatches(
                year = yearOf(absolute),
                month = monthOf(absolute),
                rewatches = counts[absolute] ?: 0,
            )
        }
    }

    /** Months since year 0, so "eleven months before January" needs no wrap-around arithmetic at the call site. */
    private fun absoluteMonthOf(year: Int, month: Int): Int = year * MONTHS_IN_YEAR + (month - 1)

    private fun yearOf(absoluteMonth: Int): Int = absoluteMonth / MONTHS_IN_YEAR

    private fun monthOf(absoluteMonth: Int): Int = absoluteMonth % MONTHS_IN_YEAR + 1

    private const val MONTHS_IN_YEAR = 12
}
