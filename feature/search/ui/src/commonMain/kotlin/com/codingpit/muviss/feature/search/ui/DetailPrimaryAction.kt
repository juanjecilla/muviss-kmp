package com.codingpit.muviss.feature.search.ui

import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.WatchStatus

/**
 * What Detail's one filled button does, read from the library state (ADR 0037, #721). It changes with the
 * state, so the most likely next step is always the one on the button.
 */
internal sealed interface PrimaryAction {
    /** Not in the library: saves it. */
    data object Add : PrimaryAction

    /** A movie nobody has watched: one tick, with an Undo. */
    data object MarkMovieWatched : PrimaryAction

    /** A watched movie: a tap opens the Watched-again dialog rather than ticking again. */
    data class WatchedAgain(val playCount: Int) : PrimaryAction

    /** A show with an aired episode still unseen: one tick on [episode], the next one in order. */
    data class TickNext(val episode: Episode) : PrimaryAction

    /** Nothing to tick, so the button is disabled and says why. [next] is the next announced episode, if any. */
    data class Waiting(val reason: Reason, val next: Episode?) : PrimaryAction {
        enum class Reason { CaughtUp, Finished, NotAiredYet }
    }
}

/**
 * The primary action for the loaded title. Shows only: the next episode is the first *aired* unseen episode
 * of the regular seasons, in air order. Season 0 specials are never "next", and an unaired episode is never
 * ticked, because `WatchProgress` requires seen to stay at or below aired.
 */
internal fun primaryActionFor(state: DetailUiState, todayEpochDay: Long): PrimaryAction {
    val details = state.details
    if (!state.saved || details == null) return PrimaryAction.Add
    if (details.type == MediaType.MOVIE) {
        return if (state.movieWatched) PrimaryAction.WatchedAgain(state.moviePlayCount) else PrimaryAction.MarkMovieWatched
    }

    val episodes = details.seasons
        .filter { it.number >= 1 }
        .sortedBy { it.number }
        .flatMap { season -> season.episodes.sortedBy { it.episodeNumber } }
    fun aired(episode: Episode) = episode.airDateEpochDay?.let { it <= todayEpochDay } == true

    val nextAired = episodes.firstOrNull { !state.isSeen(it.id) && aired(it) }
    if (nextAired != null) return PrimaryAction.TickNext(nextAired)

    val next = episodes.firstOrNull { !state.isSeen(it.id) && it.airDateEpochDay?.let { day -> day > todayEpochDay } == true }
    return when {
        state.watchStatus == WatchStatus.FINISHED -> PrimaryAction.Waiting(PrimaryAction.Waiting.Reason.Finished, null)
        episodes.none { aired(it) } -> PrimaryAction.Waiting(PrimaryAction.Waiting.Reason.NotAiredYet, next)
        else -> PrimaryAction.Waiting(PrimaryAction.Waiting.Reason.CaughtUp, next)
    }
}
