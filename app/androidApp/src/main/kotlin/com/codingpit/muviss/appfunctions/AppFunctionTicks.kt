package com.codingpit.muviss.appfunctions

import com.codingpit.muviss.feature.progress.api.EpisodeOrdering
import com.codingpit.muviss.models.Episode

/** Why an agent's tick is refused. Both are fixed text; the episode itself is never echoed back. */
internal enum class TickRefusal(val text: String) {
    NOT_IN_CATALOG("That episode is not in this show's episode list. Call getWatchNext for a valid episodeId."),
    NOT_AIRED("That episode has not aired yet, so it cannot be ticked."),
}

/**
 * The tick rule, with no I/O: an episode must be in the stored catalog and must have aired by [todayEpochDay].
 * An undated episode has not aired (EpisodeOrdering.hasAired), so it is refused too.
 */
internal fun tickRefusal(episode: Episode?, todayEpochDay: Long): TickRefusal? = when {
    episode == null -> TickRefusal.NOT_IN_CATALOG
    !EpisodeOrdering.hasAired(episode, todayEpochDay) -> TickRefusal.NOT_AIRED
    else -> null
}
