package com.codingpit.muviss.feature.triage.data

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.ProductionStatus
import com.codingpit.muviss.models.Season
import kotlinx.coroutines.CoroutineDispatcher

internal const val TODAY_EPOCH_DAY = 20_000L
internal const val NOW_EPOCH_MS = TODAY_EPOCH_DAY * 86_400_000L

internal class ImmediateDispatchers(d: CoroutineDispatcher) : AppDispatchers {
    override val default = d
    override val io = d
}

internal class FakeClock(private var millis: Long = NOW_EPOCH_MS) : AppClock {
    override fun nowEpochMs(): Long = millis

    fun advanceBy(millis: Long) {
        this.millis += millis
    }
}

/**
 * A show with [airedCount] episodes already out and [unairedCount] still to
 * come, in one season. Used to prove that "caught up" ticks exactly the aired
 * ones — the constraint `WatchProgress`'s `require(seen <= aired)` enforces.
 */
internal fun showDetails(
    id: String = "1399",
    airedCount: Int,
    unairedCount: Int = 0,
    productionStatus: ProductionStatus = ProductionStatus.RETURNING,
    includeUndatedSpecial: Boolean = false,
): MediaDetails {
    val mediaId = MediaId.tmdbTv(id)
    val main = Season(
        number = 1,
        name = "Season 1",
        episodes = List(airedCount + unairedCount) { index ->
            Episode(
                id = EpisodeId(mediaId, 1, index + 1),
                seasonNumber = 1,
                episodeNumber = index + 1,
                name = "Episode ${index + 1}",
                airDateEpochDay = if (index < airedCount) TODAY_EPOCH_DAY - (airedCount - index) else TODAY_EPOCH_DAY + index,
            )
        },
    )
    val specials = Season(
        number = 0,
        name = "Specials",
        episodes = listOf(Episode(EpisodeId(mediaId, 0, 1), 0, 1, "Behind the scenes", airDateEpochDay = null)),
    )
    return MediaDetails(
        summary = MediaSummary(mediaId, "Show $id"),
        productionStatus = productionStatus,
        seasons = if (includeUndatedSpecial) listOf(specials, main) else listOf(main),
    )
}

internal fun movieDetails(id: String = "603", title: String = "The Matrix"): MediaDetails = MediaDetails(
    summary = MediaSummary(MediaId.tmdbMovie(id), title),
    productionStatus = ProductionStatus.RELEASED,
)
