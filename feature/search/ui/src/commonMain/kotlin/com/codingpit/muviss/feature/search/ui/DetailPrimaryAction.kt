package com.codingpit.muviss.feature.search.ui

import com.codingpit.muviss.feature.progress.api.NextEpisodes
import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.WatchStatus

/** TMDB's season number for "Specials" (ADR 0037): aired and ticked from the list, never "next". */
internal const val SPECIALS_SEASON_NUMBER = 0

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
internal fun primaryActionFor(state: DetailUiState, todayEpochDay: Long, next: NextEpisodes): PrimaryAction {
    if (!state.membershipLoaded) return loading()
    // Progress is awaited for an unsaved title too: "Add to library" before the first seen emission would be a guess (#779).
    if (!state.progressLoaded) return loading()
    if (!state.saved) return PrimaryAction.Add
    val details = state.details ?: return loading()
    if (details.type == MediaType.MOVIE) {
        return if (state.movieWatched) PrimaryAction.WatchedAgain(state.moviePlayCount) else PrimaryAction.MarkMovieWatched
    }

    // Specials (season 0) are never ticked by this button, but an aired special still counts as "something has aired":
    // only when nothing at all has aired is the title "not aired yet" rather than "caught up".
    val anythingAired = details.seasons.any { season -> season.episodes.any { it.hasAired(todayEpochDay) } }

    next.aired?.let { return PrimaryAction.TickNext(it) }
    // The next-episode answer skips specials, so with only one left the title would read "Caught up" while WatchStatus
    // still counts it (ADR 0037). Finished cannot hold here: it needs every aired special seen too. Only when the setting
    // counts specials (#938): with it off, an unseen special is not progress, so the title really is caught up.
    if (state.specialsCounted) {
        firstUnseenSpecial(details, state, todayEpochDay)?.let { return PrimaryAction.SpecialUnseen(it) }
    }
    return when {
        state.watchStatus == WatchStatus.FINISHED -> PrimaryAction.Waiting(PrimaryAction.Waiting.Reason.Finished, null)
        !anythingAired -> PrimaryAction.Waiting(PrimaryAction.Waiting.Reason.NotAiredYet, next.announced)
        else -> PrimaryAction.Waiting(PrimaryAction.Waiting.Reason.CaughtUp, next.announced)
    }
}

private fun firstUnseenSpecial(details: MediaDetails, state: DetailUiState, todayEpochDay: Long): Episode? = details.seasons
    .firstOrNull { it.number == SPECIALS_SEASON_NUMBER }
    ?.episodes
    ?.firstOrNull { it.hasAired(todayEpochDay) && !state.isSeen(it.id) }

private fun loading() = PrimaryAction.Waiting(PrimaryAction.Waiting.Reason.Loading, null)

/** Whether this episode has aired by [todayEpochDay]. An undated episode has not: it is never ticked. */
internal fun Episode.hasAired(todayEpochDay: Long): Boolean = airDateEpochDay?.let { it <= todayEpochDay } == true
