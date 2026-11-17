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

    /**
     * Every regular episode is seen but an aired special is not (ADR 0037, #797). The button scrolls to the Specials
     * season, which is expanded so [special] can be ticked from its row. [special] is the first unseen one.
     */
    data class SpecialUnseen(val special: Episode) : PrimaryAction

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
internal fun primaryActionFor(state: DetailUiState, next: NextEpisodes): PrimaryAction {
    if (!state.membershipLoaded) return loading()
    // Progress is awaited for an unsaved title too: "Add to library" before the first seen emission would be a guess (#779).
    if (!state.progressLoaded) return loading()
    if (!state.saved) return PrimaryAction.Add
    val details = state.details ?: return loading()
    if (details.type == MediaType.MOVIE) {
        return if (state.movieWatched) PrimaryAction.WatchedAgain(state.moviePlayCount) else PrimaryAction.MarkMovieWatched
    }

    next.aired?.let { return PrimaryAction.TickNext(it) }
    // The next-episode answer skips specials, so with only one left the title would read "Caught up" while WatchStatus
    // still counts it (ADR 0037). Finished cannot hold here: it needs every aired special seen too.
    next.unseenSpecial?.let { return PrimaryAction.SpecialUnseen(it) }
    return when {
        state.watchStatus == WatchStatus.FINISHED -> PrimaryAction.Waiting(PrimaryAction.Waiting.Reason.Finished, null)

        // An aired special counts as "something has aired" (anyAired), so only nothing at all having aired is "not aired yet".
        !next.anyAired -> PrimaryAction.Waiting(PrimaryAction.Waiting.Reason.NotAiredYet, next.announced)

        else -> PrimaryAction.Waiting(PrimaryAction.Waiting.Reason.CaughtUp, next.announced)
    }
}

private fun loading() = PrimaryAction.Waiting(PrimaryAction.Waiting.Reason.Loading, null)
