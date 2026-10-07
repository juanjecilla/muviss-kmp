package com.codingpit.muviss.feature.progress.api

import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.Season
import kotlinx.coroutines.flow.Flow

/**
 * One viewing of one episode (or of a movie, via [EpisodeId.forMovie]).
 *
 * Watching something again used to be unrepresentable — re-ticking a seen
 * episode simply un-ticked it. Each viewing is now its own row carrying the
 * moment it happened, which is what lets the app answer "how many times, and
 * when" rather than only "yes or no". See ADR 0011.
 */
data class EpisodePlay(
    val episodeId: EpisodeId,
    val watchedAtEpochMs: Long,
)

/**
 * One title the user is part-way through, with the next episode they have not
 * seen — the answer to "what do I watch next".
 *
 * Public as of EPIC 22 because three surfaces now render it: the Progress
 * tab, the Android widget and the iOS widget. It was a `:ui` type while the
 * Progress screen was the only caller; leaving it there would have meant each
 * new surface rebuilding the collection × catalog × ticks join for itself,
 * and three answers to a question that has one.
 *
 * [nextEpisode] is null both for a title fully caught up and for one whose
 * catalog is not available yet — a distinction no caller has needed, and one
 * a widget deliberately blurs into "open the app".
 */
data class WatchNextItem(
    val mediaId: MediaId,
    val title: String,
    val posterUrl: String?,
    val nextEpisode: Episode?,
    val seenCount: Int = 0,
    val airedCount: Int = 0,
) {
    /** Fraction of aired episodes seen, for the row's progress bar; null when nothing aired. */
    val progress: Float? get() = if (airedCount > 0) seenCount / airedCount.toFloat() else null
}

/**
 * Public contract of the progress feature. Peers (search's `DetailScreen`,
 * collection's status derivation) depend on this module only — never
 * progress's domain/data/ui — to tick episodes and read watched state without
 * knowing anything about how progress is stored.
 */
interface ProgressApi {
    /** Seen episode ids for [mediaId] (movies use the single id from [EpisodeId.forMovie]). */
    fun observeSeenEpisodes(mediaId: MediaId): Flow<Set<EpisodeId>>

    /**
     * Every title currently being watched, each with its next unseen aired
     * episode, ordered as the Progress tab orders them.
     *
     * Reactive end to end: ticking an episode — from any surface — advances
     * the item, and a title leaving `Watching` drops out. The catalog behind
     * it is read from local storage before the network (ADR 0015), so this
     * answers offline, which is the only reason a home-screen widget can
     * render at all.
     */
    fun observeWatchNext(): Flow<List<WatchNextItem>>

    /**
     * Re-fetches and stores the episode catalog of every title being
     * watched — the background-refresh entry point (ADR 0015).
     *
     * Called from the platform hosts' existing 12-hour refresh, so the
     * widgets keep naming the right episode as new ones air without the app
     * ever being opened. Failures are absorbed per title: a catalog that
     * cannot be refreshed keeps the one already stored.
     */
    suspend fun refreshWatchNextCatalogs()

    /**
     * [mediaId]'s stored episode catalog (the `episode` cache, ADR 0015), or
     * null when nothing is stored — what offline-first Detail renders before
     * the network answers (EPIC 30, #73). Never fetches.
     */
    suspend fun storedSeasons(mediaId: MediaId): List<Season>? = null

    /**
     * Every distinct epoch-day (UTC, [com.codingpit.muviss.core.common.todayEpochDay]'s
     * convention) carrying at least one recorded viewing, across every title —
     * the raw calendar the profile feature derives its watch-streak stat from.
     *
     * Read from the play history (ADR 0011), not from tick timestamps. Under
     * the old reading, watching an old episode again *moved* its day to today
     * instead of adding one, silently erasing a past streak day; now each
     * viewing contributes its own. Undoing a viewing still removes its day if
     * nothing else shares it.
     */
    fun observeSeenActivityEpochDays(): Flow<Set<Long>>

    /** How many times each episode of [mediaId] has been watched; absent means never. */
    fun observePlayCounts(mediaId: MediaId): Flow<Map<EpisodeId, Int>>

    /** Every recorded viewing of [episodeId], newest first — episode detail's watch history. */
    fun observePlays(episodeId: EpisodeId): Flow<List<EpisodePlay>>

    /**
     * How many *rewatches* each title has accumulated since [sinceEpochMs]
     * (0 for all time) — the profile's "most rewatched" ranking.
     *
     * A rewatch is a viewing that has an earlier viewing of the same episode
     * behind it, at any date. The bound applies to the rewatch itself and
     * never to the first viewing, so an episode first seen last December and
     * watched again in March counts as a rewatch *in March* rather than
     * disappearing at the year boundary. A show's number therefore counts
     * episode rewatches and a film's counts viewings beyond the first; a
     * first watch-through scores zero however long the show is. See ADR 0012.
     *
     * Titles no longer in the library are still reported here — filtering to
     * the library is the caller's decision, and the profile ranking makes it
     * by joining these counts onto what the collection holds.
     */
    fun observeRewatchCounts(sinceEpochMs: Long): Flow<Map<MediaId, Int>>

    /**
     * The timestamp of every rewatch since [sinceEpochMs], across every
     * title — the raw input the profile's rewatch trend buckets into months.
     * Same definition of "rewatch" as [observeRewatchCounts].
     */
    fun observeRewatchTimestamps(sinceEpochMs: Long): Flow<List<Long>>

    /**
     * Ticks a single episode, e.g. a per-episode checkmark in Detail.
     *
     * `true` records a first viewing and is idempotent — re-asserting "seen"
     * is not the same as saying "watched again", which is [recordPlay].
     * `false` clears the episode's history outright.
     */
    suspend fun setEpisodeSeen(episodeId: EpisodeId, seen: Boolean)

    /** "I watched this again": adds a viewing, leaving the ones before it intact. */
    suspend fun recordPlay(episodeId: EpisodeId)

    /**
     * "I ticked that by mistake": drops the most recent viewing only. An
     * episode watched three times goes to two and stays seen; one watched
     * once becomes unseen. Never destroys history behind the mistake — that
     * is [clearPlays], which episode detail offers explicitly.
     */
    suspend fun removeLatestPlay(episodeId: EpisodeId)

    /** Forgets every viewing of [episodeId] and un-ticks it. */
    suspend fun clearPlays(episodeId: EpisodeId)

    /**
     * Marks every episode of [season] that has aired by [todayEpochDay] as
     * seen, and only those, returning the ids it actually ticked so an undo
     * can take back exactly that.
     *
     * Aired-only is not a nicety. Ticking a season's unaired episodes makes
     * `seenEpisodes` exceed `airedEpisodes`, and
     * [WatchProgress][com.codingpit.muviss.core.model.WatchProgress]
     * `require`s otherwise — so the library screen would throw the next time
     * it derived that title's status. Already-seen episodes are skipped
     * rather than re-played: catching up on a season is not a rewatch.
     */
    suspend fun markSeasonAiredSeen(season: Season, todayEpochDay: Long): List<EpisodeId>

    /** [markSeasonAiredSeen] across every season of a show — the "mark whole show seen" action. */
    suspend fun markShowAiredSeen(seasons: List<Season>, todayEpochDay: Long): List<EpisodeId>

    /** Reverses [markSeasonAiredSeen]: drops the most recent viewing of each seen episode in [season]. */
    suspend fun unmarkSeason(season: Season)

    /** Reverses [markShowAiredSeen] across every season. */
    suspend fun unmarkShow(seasons: List<Season>)

    /** Takes back exactly the ids a bulk mark reported writing — the undo snackbar's action. */
    suspend fun undoBulkMark(episodeIds: List<EpisodeId>)

    /** Marks every episode at or before [target] across [seasons] as seen — the "catch me up" action. Already-seen episodes keep the history they have. */
    suspend fun markPreviousSeen(seasons: List<Season>, target: EpisodeId)

    /**
     * Marks every episode that has aired by [todayEpochDay] as seen, and
     * nothing else — the "I am fully up to date" action triage's `CaughtUp`
     * verdict uses. Unlike [markPreviousSeen] this never ticks an unaired or
     * undated episode, which would make seen exceed aired and break status
     * derivation (ADR 0005).
     */
    suspend fun markAllAiredSeen(seasons: List<Season>, todayEpochDay: Long)

    /**
     * Un-ticks every episode (or the movie tick) for [mediaId]. Triage's undo
     * uses it to reverse the progress a verdict wrote; without it, taking back
     * a "caught up" swipe would leave the title reading as fully watched.
     */
    suspend fun clearProgress(mediaId: MediaId)

    /** Toggles a movie's watched flag. */
    suspend fun setMovieWatched(mediaId: MediaId, watched: Boolean)
}
