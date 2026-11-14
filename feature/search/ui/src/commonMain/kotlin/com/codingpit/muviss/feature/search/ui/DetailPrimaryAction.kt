package com.codingpit.muviss.feature.search.ui

import com.codingpit.muviss.feature.progress.api.NextEpisodes
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
        enum class Reason {
            /** Membership or progress has not reported yet: a disabled placeholder, never a guess at the action (#779). */
            Loading,
            CaughtUp,
            Finished,
            NotAiredYet,
        }
    }
}

/**
 * The primary action for the loaded title. Shows only: [next] is the answer `ProgressApi.nextEpisodes` gives for
 * the seasons on screen (the same ordering Progress uses), so an unaired episode is never ticked, and specials
 * are never "next", because `WatchProgress` requires seen to stay at or below aired.
 *
 * Until the library and the progress flow have both reported, the answer is [PrimaryAction.Waiting] with
 * [PrimaryAction.Waiting.Reason.Loading]. Without that, a saved title that is already watched reads
 * "Add to library" or "Mark S1·E1 seen" for the moment before the first emission.
 */
internal fun primaryActionFor(state: DetailUiState, todayEpochDay: Long, next: NextEpisodes): PrimaryAction {
    if (!state.membershipLoaded) return loading()
    if (!state.saved) return PrimaryAction.Add
    val details = state.details ?: return loading()
    if (!state.progressLoaded) return loading()
    if (details.type == MediaType.MOVIE) {
        return if (state.movieWatched) PrimaryAction.WatchedAgain(state.moviePlayCount) else PrimaryAction.MarkMovieWatched
    }

    // Specials (season 0) are never ticked by this button, but an aired special still counts as "something has aired":
    // only when nothing at all has aired is the title "not aired yet" rather than "caught up".
    val anythingAired = details.seasons.any { season -> season.episodes.any { it.hasAired(todayEpochDay) } }

    next.aired?.let { return PrimaryAction.TickNext(it) }
    return when {
        state.watchStatus == WatchStatus.FINISHED -> PrimaryAction.Waiting(PrimaryAction.Waiting.Reason.Finished, null)
        !anythingAired -> PrimaryAction.Waiting(PrimaryAction.Waiting.Reason.NotAiredYet, next.announced)
        else -> PrimaryAction.Waiting(PrimaryAction.Waiting.Reason.CaughtUp, next.announced)
    }
}

private fun loading() = PrimaryAction.Waiting(PrimaryAction.Waiting.Reason.Loading, null)

/** Whether this episode has aired by [todayEpochDay]. An undated episode has not: it is never ticked. */
internal fun Episode.hasAired(todayEpochDay: Long): Boolean = airDateEpochDay?.let { it <= todayEpochDay } == true
