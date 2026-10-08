package com.codingpit.muviss.feature.search.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.connectivity.ConnectivityMonitor
import com.codingpit.muviss.core.common.connectivity.reconnections
import com.codingpit.muviss.core.common.crash.launchInReporting
import com.codingpit.muviss.core.common.crash.launchReporting
import com.codingpit.muviss.core.common.todayEpochDay
import com.codingpit.muviss.core.designsystem.text.UiText
import com.codingpit.muviss.core.designsystem.text.toUiText
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.feature.search.domain.MediaDetailUseCase
import com.codingpit.muviss.feature.search.domain.MoreLikeThisUseCase
import com.codingpit.muviss.feature.search.domain.WatchProvidersUseCase
import com.codingpit.muviss.feature.search.ui.generated.resources.Res
import com.codingpit.muviss.feature.search.ui.generated.resources.error_generic
import com.codingpit.muviss.feature.search.ui.generated.resources.refresh_failed
import com.codingpit.muviss.feature.search.ui.generated.resources.undo_season_seen
import com.codingpit.muviss.feature.search.ui.generated.resources.undo_show_seen
import com.codingpit.muviss.feature.triage.api.TriageApi
import com.codingpit.muviss.feature.triage.api.TriageVerdict
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.Season
import com.codingpit.muviss.models.WatchProviders
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

data class DetailUiState(
    val loading: Boolean = true,
    val details: MediaDetails? = null,
    val error: UiText? = null,
    /**
     * Set when [details] is the saved copy and the refresh from TMDB failed —
     * e.g. offline. The screen keeps showing the saved title with a banner and
     * Retry, instead of an error page for something already on the device
     * (EPIC 30, #73).
     */
    val staleNotice: UiText? = null,
    val saved: Boolean = false,
    val favorite: Boolean = false,
    /** Per-show new-episode notification opt-out (EPIC 5); only meaningful while [saved] is true. */
    val notificationsMuted: Boolean = false,
    /** Personal 1-10 rating, or null for unrated (EPIC 15); only meaningful while [saved] is true. */
    val rating: Int? = null,
    /** Personal free-text note, or null for none (EPIC 15); only meaningful while [saved] is true. */
    val note: String? = null,
    /**
     * Whether the user would watch this again with someone (EPIC 41, ADR 0022).
     * Null means they have never answered, which is not the same as "no": an
     * unanswered title follows the co-watch default, an answered one does not.
     */
    val revisitWillingness: Boolean? = null,
    /** Seen episode ids (movies use the single id from [EpisodeId.forMovie]) — drives checkmarks, season bars, and the movie toggle. */
    val seenEpisodes: Set<EpisodeId> = emptySet(),
    /** Null while loading; an empty [WatchProviders] once loaded means "hide the section" — no failure surfaced, it's a nice-to-have. */
    val watchProviders: WatchProviders? = null,
    /** "More like this" row (EPIC 16): recommendations, falling back to similar titles when TMDB has no recommendations for this title. Empty hides the row. */
    val moreLikeThis: List<MediaSummary> = emptyList(),
    /** True when this title was skipped during triage (ADR 0010) — the only way back once the undo snackbar has gone. */
    val skipped: Boolean = false,
    /** The day a pending Snooze brings this title back (EPIC 42), or null if it has none. */
    val snoozedUntilEpochDay: Long? = null,
    /** How many times each episode has been watched (ADR 0011); absent means never. */
    val playCounts: Map<EpisodeId, Int> = emptyMap(),
    /** Set right after a bulk mark, so its snackbar can take back exactly those ticks. */
    val pendingUndo: BulkMarkUndo? = null,
) {
    fun isSeen(episodeId: EpisodeId): Boolean = episodeId in seenEpisodes

    /** Viewings recorded for [episodeId]; 0 for an episode never watched. */
    fun playCountOf(episodeId: EpisodeId): Int = playCounts[episodeId] ?: 0

    fun seenCountIn(season: Season): Int = season.episodes.count { it.id in seenEpisodes }

    val movieWatched: Boolean
        get() = details?.let { EpisodeId.forMovie(it.id) in seenEpisodes } ?: false

    /** How many times the movie has been watched, for the "watched 3x" line under its toggle. */
    val moviePlayCount: Int
        get() = details?.let { playCountOf(EpisodeId.forMovie(it.id)) } ?: 0
}

/**
 * What a bulk "mark seen" just wrote, so its snackbar can undo precisely that
 * and nothing else.
 *
 * The ids matter: a season mark skips episodes already seen, so undoing by
 * re-deriving "everything in the season" would strip viewings the action never
 * added.
 */
data class BulkMarkUndo(
    val episodeIds: List<EpisodeId>,
    val message: UiText,
)

/**
 * Loads a title's detail and mirrors its library membership and watch
 * progress through [CollectionApi] and [ProgressApi] — the collection and
 * progress features' public contracts, never their domain/data/ui — so this
 * feature can offer add/remove, favorite, and episode-tracking controls
 * without depending on how either is implemented.
 */
@Suppress("LongParameterList") // the seventh is the connectivity signal (#249), defaulted so tests can leave it out
class DetailViewModel(
    private val mediaId: MediaId,
    private val loadDetail: MediaDetailUseCase,
    private val peers: DetailPeers,
    private val loadWatchProviders: WatchProvidersUseCase,
    private val loadMoreLikeThis: MoreLikeThisUseCase,
    private val clock: AppClock,
    connectivity: ConnectivityMonitor = ConnectivityMonitor.AlwaysOnline,
) : ViewModel() {

    private val collectionApi: CollectionApi get() = peers.collection
    private val progressApi: ProgressApi get() = peers.progress
    private val triageApi: TriageApi get() = peers.triage

    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    /** "Today" for aired-vs-unaired decisions in the season list. */
    val todayEpochDay: Long get() = clock.todayEpochDay()

    init {
        load()
        triageApi.observeDecision(mediaId)
            .onEach { decision -> _state.update { it.copy(skipped = decision?.verdict == TriageVerdict.SKIP) } }
            .launchInReporting(viewModelScope)
        // A Snooze and a decision are mutually exclusive by construction, so
        // the two banners can never both show (ADR 0023).
        triageApi.observeSnooze(mediaId)
            .onEach { snooze -> _state.update { it.copy(snoozedUntilEpochDay = snooze?.dueAtEpochDay) } }
            .launchInReporting(viewModelScope)
        collectionApi.observeMembership(mediaId)
            .onEach { membership ->
                _state.update {
                    it.copy(
                        saved = membership != null,
                        favorite = membership?.favorite ?: false,
                        notificationsMuted = membership?.notificationsMuted ?: false,
                        rating = membership?.rating,
                        note = membership?.note,
                        revisitWillingness = membership?.revisitWillingness,
                    )
                }
            }
            .launchInReporting(viewModelScope)
        progressApi.observeSeenEpisodes(mediaId)
            .onEach { seen -> _state.update { it.copy(seenEpisodes = seen) } }
            .launchInReporting(viewModelScope)
        progressApi.observePlayCounts(mediaId)
            .onEach { counts -> _state.update { it.copy(playCounts = counts) } }
            .launchInReporting(viewModelScope)
        // Back online with an error page or a stale notice up: fetch again (#249).
        connectivity.reconnections()
            .onEach { if (_state.value.error != null || _state.value.staleNotice != null) load() }
            .launchInReporting(viewModelScope)
    }

    /**
     * Offline-first (EPIC 30, #73): a saved title renders from the library
     * snapshot and the stored episode catalog at once, then the network answer
     * replaces it. A failed refresh over saved data is a [DetailUiState.staleNotice],
     * not an error — only a title with nothing on the device shows the error page.
     */
    fun load() {
        viewModelScope.launchReporting {
            if (_state.value.details == null) {
                val local = savedCopy()
                _state.update { it.copy(loading = local == null, details = local, error = null, staleNotice = null) }
            } else {
                _state.update { it.copy(error = null, staleNotice = null) }
            }
            loadDetail(mediaId).fold(
                onSuccess = { d -> _state.update { it.copy(loading = false, details = d, staleNotice = null) } },
                onFailure = { e ->
                    _state.update {
                        if (it.details != null) {
                            it.copy(loading = false, staleNotice = e.toUiText(UiText.Resource(Res.string.refresh_failed)))
                        } else {
                            it.copy(loading = false, error = e.toUiText(UiText.Resource(Res.string.error_generic)))
                        }
                    }
                },
            )
        }
        loadWhereToWatch()
        loadMoreLikeThisRow()
    }

    /** The library snapshot plus the stored episode catalog, or null for a title that is not saved. */
    private suspend fun savedCopy(): MediaDetails? {
        val saved = runCatching { collectionApi.savedDetails(mediaId) }.getOrNull() ?: return null
        val seasons = if (mediaId.type == MediaType.TV) runCatching { progressApi.storedSeasons(mediaId) }.getOrNull().orEmpty() else emptyList()
        return saved.copy(seasons = seasons)
    }

    /**
     * Loaded independently of [load]: a provider outage here shouldn't block
     * showing the title's details, so failure just leaves the section hidden
     * rather than surfacing an error.
     */
    private fun loadWhereToWatch() {
        viewModelScope.launchReporting {
            loadWatchProviders(mediaId).onSuccess { providers ->
                _state.update { it.copy(watchProviders = providers) }
            }
        }
    }

    /**
     * Loaded independently of [load], same as [loadWhereToWatch]: "More like
     * this" is a nice-to-have, so a failure just leaves the row hidden rather
     * than surfacing an error. [MoreLikeThisUseCase] owns the
     * recommendations-with-similar-fallback choice.
     */
    private fun loadMoreLikeThisRow() {
        viewModelScope.launchReporting {
            val items = loadMoreLikeThis(mediaId).getOrNull()?.items.orEmpty()
            _state.update { it.copy(moreLikeThis = items) }
        }
    }

    /** Clears a SKIP so the title can come back around in the deck. */
    fun unskip() {
        viewModelScope.launchReporting { triageApi.restore(mediaId) }
    }

    /** Drops a pending Snooze so the title is deck-eligible again now (EPIC 42). */
    fun unsnooze() {
        viewModelScope.launchReporting { triageApi.unsnooze(mediaId) }
    }

    /** Adds the loaded title to the library, or removes it if already saved. */
    fun toggleSaved() {
        val details = _state.value.details ?: return
        viewModelScope.launchReporting {
            if (_state.value.saved) collectionApi.remove(mediaId) else collectionApi.add(details)
        }
    }

    fun toggleFavorite() {
        viewModelScope.launchReporting { collectionApi.setFavorite(mediaId, !_state.value.favorite) }
    }

    /** Mutes/un-mutes this show's new-episode notifications (EPIC 5), independent of the global Settings toggle. */
    fun toggleNotificationsMuted() {
        viewModelScope.launchReporting { collectionApi.setNotificationsMuted(mediaId, !_state.value.notificationsMuted) }
    }

    /** Sets the personal rating (1-10), or clears it (EPIC 15) if [rating] is the one already set — tapping the same star twice un-rates. */
    fun setRating(rating: Int) {
        val next = if (_state.value.rating == rating) null else rating
        viewModelScope.launchReporting { collectionApi.setRating(mediaId, next) }
    }

    /** Explicitly clears the personal rating (EPIC 15). */
    fun clearRating() {
        viewModelScope.launchReporting { collectionApi.setRating(mediaId, null) }
    }

    /** Persists the personal note (EPIC 15); collection's `:api` normalizes a blank note to null. */
    fun setNote(note: String) {
        viewModelScope.launchReporting { collectionApi.setNote(mediaId, note) }
    }

    /**
     * Records whether this is one the user would watch again with someone
     * (EPIC 41). Cycles yes → no → unanswered, because "never asked" is a real
     * third state and losing it would silently answer for every other title.
     */
    fun cycleRevisitWillingness() {
        val next = when (_state.value.revisitWillingness) {
            null -> true
            true -> false
            false -> null
        }
        viewModelScope.launchReporting { collectionApi.setRevisitWillingness(mediaId, next) }
    }

    /** Ticks a single episode's checkmark. */
    fun toggleEpisodeSeen(episodeId: EpisodeId) {
        viewModelScope.launchReporting { progressApi.setEpisodeSeen(episodeId, !_state.value.isSeen(episodeId)) }
    }

    /**
     * Marks every *aired* episode of [season] as seen and parks an undo.
     *
     * Aired-only is the rule the whole feature hangs on: ticking unaired
     * episodes makes seen exceed aired, which `WatchProgress` forbids and the
     * library screen would then throw on. See `ProgressApi.markSeasonAiredSeen`.
     */
    fun markSeasonSeen(season: Season) {
        viewModelScope.launchReporting {
            val written = progressApi.markSeasonAiredSeen(season, clock.todayEpochDay())
            offerUndo(written, UiText.Resource(Res.string.undo_season_seen, season.name))
        }
    }

    /** Reverses [markSeasonSeen]: drops the newest viewing of each seen episode in [season]. */
    fun unmarkSeason(season: Season) {
        viewModelScope.launchReporting {
            progressApi.unmarkSeason(season)
            _state.update { it.copy(pendingUndo = null) }
        }
    }

    /** The "mark whole show seen" action; the caller confirms first. */
    fun markShowSeen() {
        val seasons = _state.value.details?.seasons ?: return
        viewModelScope.launchReporting {
            val written = progressApi.markShowAiredSeen(seasons, clock.todayEpochDay())
            offerUndo(written, UiText.Resource(Res.string.undo_show_seen))
        }
    }

    /** Reverses [markShowSeen] across every season. */
    fun unmarkShow() {
        val seasons = _state.value.details?.seasons ?: return
        viewModelScope.launchReporting {
            progressApi.unmarkShow(seasons)
            _state.update { it.copy(pendingUndo = null) }
        }
    }

    /** Takes back exactly the ticks the last bulk mark wrote. */
    fun undoBulkMark() {
        val undo = _state.value.pendingUndo ?: return
        viewModelScope.launchReporting {
            progressApi.undoBulkMark(undo.episodeIds)
            _state.update { it.copy(pendingUndo = null) }
        }
    }

    /** Acknowledges the undo snackbar without undoing anything. */
    fun dismissUndo() {
        _state.update { it.copy(pendingUndo = null) }
    }

    /** "I watched this again": records another viewing, leaving earlier ones intact. */
    fun recordRewatch(episodeId: EpisodeId) {
        viewModelScope.launchReporting { progressApi.recordPlay(episodeId) }
    }

    /** "I ticked that by mistake": drops the newest viewing only. */
    fun undoLatestPlay(episodeId: EpisodeId) {
        viewModelScope.launchReporting { progressApi.removeLatestPlay(episodeId) }
    }

    /** Forgets an episode's entire watch history — the explicit, destructive one. */
    fun clearEpisodeHistory(episodeId: EpisodeId) {
        viewModelScope.launchReporting { progressApi.clearPlays(episodeId) }
    }

    /** A bulk mark that ticked nothing (already caught up) has nothing to undo, so it offers none. */
    private fun offerUndo(written: List<EpisodeId>, message: UiText) {
        if (written.isEmpty()) return
        _state.update { it.copy(pendingUndo = BulkMarkUndo(written, message)) }
    }

    /** "I'm caught up through here": marks every episode at or before [episodeId] as seen. */
    fun markPreviousSeen(episodeId: EpisodeId) {
        val seasons = _state.value.details?.seasons ?: return
        viewModelScope.launchReporting { progressApi.markPreviousSeen(seasons, episodeId) }
    }

    /** Toggles a movie's watched flag. */
    fun toggleMovieWatched() {
        viewModelScope.launchReporting { progressApi.setMovieWatched(mediaId, !_state.value.movieWatched) }
    }
}

/**
 * The peer features Detail reads through, bundled so this screen's ViewModel
 * constructor stays inside detekt's `LongParameterList` budget — the same
 * trick collection's `CollectionToggles` uses. Each is still a peer's `:api`
 * and nothing more (ADR 0004).
 */
class DetailPeers(
    val collection: CollectionApi,
    val progress: ProgressApi,
    val triage: TriageApi,
)
