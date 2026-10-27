package com.codingpit.muviss.feature.progress.domain

import com.codingpit.muviss.feature.progress.api.Completion
import com.codingpit.muviss.feature.progress.api.CompletionKind
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The derived [WatchStatus] of a title the person has in their library, or null when it is not in
 * it. A seam rather than a `CollectionApi` parameter: progress's data layer resolves collection
 * lazily (see [WatchNextUseCase] for the construction cycle that forces it).
 */
fun interface TitleStatusSource {
    suspend fun statusOf(mediaId: MediaId): WatchStatus?
}

/**
 * Notices that a tick the person just made completed a title (EPIC 51, #397, ADR 0032).
 *
 * It sits around one write, not behind a flow of status changes, for the reason the whole feature
 * hangs on: the repository cannot tell a tap from an import row, and a flow would see both. Only
 * [TickEpisodeUseCase] and [TickMovieUseCase] call [around], and only the in-app entry points call
 * those. Every other writer (widgets, Triage, import, sync pull, restore, bulk marks) goes through
 * the plain use cases and so cannot emit, which is what "never a Completion" means here.
 *
 * Status is compared before and after the write, synchronously: [WatchStatus] is derived, so the
 * second read already sees the tick. The read, write, read runs under one lock: two quick taps on a
 * show's last two episodes would otherwise both read Watching before either write, both read Watched
 * after, and emit two Completions for one. Ticks are hand-made and short, so one lock for every title
 * costs nothing a person could notice.
 *
 * Detection never blocks the tick. A status that cannot be read (the derivation throws for a title
 * whose stored counts break `WatchProgress`'s invariant, and the source derives the whole library)
 * reads as unknown, which detects nothing, and the write still happens.
 */
class CompletionDetector(private val statuses: TitleStatusSource) {

    private val lock = Mutex()

    private val emitted = MutableSharedFlow<Completion>(extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Every Completion detected so far in this process, hot. */
    val completions: Flow<Completion> = emitted.asSharedFlow()

    /** Runs [write], then emits a [Completion] if it moved the title of [mediaId] to Watched or Finished. */
    suspend fun around(mediaId: MediaId, seen: Boolean, write: suspend () -> Unit) {
        // Unticking can never complete a title, so it skips the two reads.
        if (!seen) return write()
        lock.withLock {
            val before = statusOrNull(mediaId)
            write()
            val after = statusOrNull(mediaId)
            completionOf(mediaId, before, after)?.let(emitted::tryEmit)
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Any failure to read means "unknown", never a failed tick.
    private suspend fun statusOrNull(mediaId: MediaId): WatchStatus? = try {
        statuses.statusOf(mediaId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val BUFFER = 8

        /** Detects nothing. For tests and previews that tick without a library behind them. */
        val None = CompletionDetector { null }

        /**
         * The Completion, if any, that moving from [before] to [after] is. Only a move *into* Watched
         * or Finished from a state that was neither counts: Watched to Finished (a show ended after a
         * refresh) is nobody's tick, and a title that was already complete stays so.
         */
        fun completionOf(mediaId: MediaId, before: WatchStatus?, after: WatchStatus?): Completion? {
            if (before == null || after == null) return null
            val wasIncomplete = before == WatchStatus.NOT_STARTED || before == WatchStatus.WATCHING
            val kind = when {
                !wasIncomplete -> return null
                after == WatchStatus.FINISHED -> CompletionKind.FINISHED
                after == WatchStatus.WATCHED && mediaId.type == MediaType.MOVIE -> CompletionKind.FINISHED
                after == WatchStatus.WATCHED -> CompletionKind.CAUGHT_UP
                else -> return null
            }
            return Completion(mediaId, kind)
        }
    }
}

/** [ToggleEpisodeSeenUseCase] for a tick the person makes by hand; finishing the title emits a Completion. */
class TickEpisodeUseCase(private val toggle: ToggleEpisodeSeenUseCase, private val detector: CompletionDetector) {
    suspend operator fun invoke(episodeId: EpisodeId, seen: Boolean) = detector.around(episodeId.show, seen) { toggle(episodeId, seen) }
}

/** [SetMovieWatchedUseCase] for a tick the person makes by hand; watching the movie emits a Completion. */
class TickMovieUseCase(private val setWatched: SetMovieWatchedUseCase, private val detector: CompletionDetector) {
    suspend operator fun invoke(mediaId: MediaId, watched: Boolean) = detector.around(mediaId, watched) { setWatched(mediaId, watched) }
}

/** The in-app tick writes, bundled for `DefaultProgressApi`'s constructor budget like [ProgressMutations]. */
class ProgressTicks(
    val tickEpisode: TickEpisodeUseCase,
    val tickMovie: TickMovieUseCase,
    val detector: CompletionDetector,
)
