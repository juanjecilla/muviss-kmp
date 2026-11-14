package com.codingpit.muviss.feature.onboarding.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.core.common.backup.platformCanBackUp
import com.codingpit.muviss.core.common.crash.launchInReporting
import com.codingpit.muviss.core.common.crash.launchReporting
import com.codingpit.muviss.core.common.flags.FeatureFlags
import com.codingpit.muviss.core.common.notifications.SystemNotificationSettings
import com.codingpit.muviss.core.common.notifications.platformCanNotify
import com.codingpit.muviss.core.designsystem.component.TmdbKeyProblem
import com.codingpit.muviss.feature.onboarding.api.OnboardingApi
import com.codingpit.muviss.feature.settings.api.NotificationsChange
import com.codingpit.muviss.feature.settings.api.SaveTmdbKeyResult
import com.codingpit.muviss.feature.settings.api.SettingsApi
import com.codingpit.muviss.feature.settings.api.TasteItem
import com.codingpit.muviss.feature.settings.api.TasteProfileApi
import com.codingpit.muviss.feature.settings.api.TmdbDataSource
import com.codingpit.muviss.feature.settings.api.TmdbKeyApi
import com.codingpit.muviss.models.GenreKey
import com.codingpit.muviss.models.MediaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll

/** The pages, in order. Three are conditional; see [pagesFor]. */
enum class OnboardingPage { WELCOME, LIBRARY, WATCH_NEXT, DISCOVER, IMPORT, DEVICE_BACKUP, NOTIFICATIONS, TMDB_KEY, MUTED_GENRES, TASTE, DONE }

/**
 * The pages this device shows: the alerts page needs a platform that notifies (not web), the backup page needs a
 * platform whose OS backup Muviss can honour (Device Backup, ADR 0034), and the TMDB key page appears only while
 * the app offers an Own TMDB Key (`byokEnabled`). The Taste page follows the mutes, so a genre already muted is
 * not the next thing offered.
 */
fun pagesFor(canNotify: Boolean, byokOffered: Boolean, canBackUp: Boolean): List<OnboardingPage> = OnboardingPage.entries.filter {
    when (it) {
        OnboardingPage.NOTIFICATIONS -> canNotify
        OnboardingPage.TMDB_KEY -> byokOffered
        OnboardingPage.DEVICE_BACKUP -> canBackUp
        else -> true
    }
}

/**
 * The genres the Muted Genres page offers (ADR 0031): TMDB's TV Talk, News, Reality and Soap. The name
 * is what Settings will list offline; the page's own copy is localized separately.
 */
enum class MutedGenreChoice(val genreId: String, val tmdbName: String) {
    TALK("10767", "Talk"),
    NEWS("10763", "News"),
    REALITY("10764", "Reality"),
    SOAP("10766", "Soap"),
    ;

    val key: GenreKey get() = GenreKey(MediaType.TV, genreId)
}

data class OnboardingUiState(
    /** Empty until the flags that decide it have been read; the screen draws nothing meanwhile. */
    val pages: List<OnboardingPage> = emptyList(),
    val index: Int = 0,
    val notificationsEnabled: Boolean = false,
    /** Device Backup (ADR 0034): what the backup page's switch shows. Off until the person turns it on. */
    val deviceBackupEnabled: Boolean = false,
    /** The user asked for alerts and the OS said no, so the switch snapped back; the page says why. */
    val notificationsDenied: Boolean = false,
    val keySource: TmdbDataSource = TmdbDataSource.MUVISS,
    val keyChecking: Boolean = false,
    val keyProblem: TmdbKeyProblem? = null,
    /** What is muted right now; the page's switches read it. Starts as the shipped default (ADR 0031). */
    val mutedGenres: Set<MutedGenreChoice> = emptySet(),
    /** Poster urls per genre, best-effort: absent offline, and the page shows its text examples alone. */
    val genrePosters: Map<MutedGenreChoice, List<String>> = emptyMap(),
    /** [TasteItem.id]s the person currently likes; the Taste page's chips read it. */
    val likedTaste: Set<String> = emptySet(),
) {
    val page: OnboardingPage? get() = pages.getOrNull(index)
    val isFirst: Boolean get() = index == 0
    val isLast: Boolean get() = index == pages.lastIndex
}

/**
 * The introduction (EPIC 47). Every page writes what it changes **as it is changed**, through
 * Settings' and the key's own doors, so Skip needs to do nothing but end the flow: it keeps
 * whatever was already set and marks onboarding completed.
 */
@Suppress("LongParameterList") // one Koin dependency per contract the pages write through; the seventh is the Taste Profile (#583)
class OnboardingViewModel(
    private val onboarding: OnboardingApi,
    private val settings: SettingsApi,
    private val tmdbKey: TmdbKeyApi,
    featureFlags: FeatureFlags,
    private val systemNotifications: SystemNotificationSettings,
    private val tasteProfile: TasteProfileApi,
    /** Whether the backup page is offered; the platform's answer in the app, a fixed one in tests. */
    private val canBackUp: Boolean = platformCanBackUp,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    /**
     * Every write this page starts, so [finish] can wait for them. Leaving cancels the view model's scope,
     * which would otherwise drop a write still in flight. Only touched on the main thread.
     */
    private val pendingWrites = mutableListOf<Job>()

    init {
        viewModelScope.launchReporting {
            // Read once: pages appearing or vanishing under a user mid-flow would move their place.
            val byok = featureFlags.byokEnabled.first()
            _state.update { it.copy(pages = pagesFor(platformCanNotify, byok, canBackUp)) }
        }
        settings.observeNotificationsEnabled()
            .onEach { enabled -> _state.update { it.copy(notificationsEnabled = enabled) } }
            .launchInReporting(viewModelScope)
        settings.observeDeviceBackupEnabled()
            .onEach { enabled -> _state.update { it.copy(deviceBackupEnabled = enabled) } }
            .launchInReporting(viewModelScope)
        tmdbKey.observeSource()
            .onEach { source -> _state.update { it.copy(keySource = source) } }
            .launchInReporting(viewModelScope)
        settings.observeMuting()
            .onEach { muting ->
                val muted = MutedGenreChoice.entries.filterTo(linkedSetOf()) { it.key in muting.configured.keys }
                _state.update { it.copy(mutedGenres = muted) }
            }
            .launchInReporting(viewModelScope)
        tasteProfile.observe()
            .onEach { items -> _state.update { it.copy(likedTaste = items.mapTo(linkedSetOf()) { item -> item.id }) } }
            .launchInReporting(viewModelScope)
        loadGenrePosters()
    }

    /**
     * Likes or unlikes one Taste chip. Written as it is tapped, like every other page. The API refuses a
     * muted genre; the Taste page offers none, so the refusal cannot happen from here. Each tap waits for
     * the one before it, so a quick off-and-on lands in order.
     */
    fun onTasteToggled(item: TasteItem, liked: Boolean) {
        val previous = tasteWrite
        tasteWrite = write {
            previous?.join()
            tasteProfile.setLiked(item, liked)
        }
    }

    /** The latest Taste write; each one waits for the one before it, see [onTasteToggled]. */
    private var tasteWrite: Job? = null

    private fun write(block: suspend CoroutineScope.() -> Unit): Job {
        pendingWrites.removeAll { it.isCompleted }
        return viewModelScope.launchReporting(block).also { pendingWrites += it }
    }

    /**
     * Fetched once, in the background, and never allowed to matter: the page already reads fine from
     * its text examples, so a failure (offline, no key) just leaves the strip out.
     */
    private fun loadGenrePosters() {
        viewModelScope.launchReporting {
            MutedGenreChoice.entries.forEach { choice ->
                val posters = settings.examplesFor(choice.key).mapNotNull { it.posterUrl }
                if (posters.isNotEmpty()) _state.update { it.copy(genrePosters = it.genrePosters + (choice to posters)) }
            }
        }
    }

    fun onGenreMutedToggled(choice: MutedGenreChoice, muted: Boolean) {
        write { settings.setGenreMuted(choice.key, choice.tmdbName, muted) }
    }

    fun next() = _state.update { if (it.isLast) it else it.copy(index = it.index + 1) }

    fun back() = _state.update { if (it.isFirst) it else it.copy(index = it.index - 1) }

    /** Start and Skip alike: whatever the pages wrote stays, and the introduction is done. */
    fun finish(onFinished: () -> Unit) {
        viewModelScope.launchReporting {
            // Leaving clears this view model and cancels its scope, so a choice still being written
            // would be lost; wait for every one first.
            pendingWrites.toList().joinAll()
            onboarding.markCompleted()
            onFinished()
        }
    }

    fun onNotificationsToggled(enabled: Boolean) {
        write {
            val change = settings.setNotificationsEnabled(enabled)
            _state.update { it.copy(notificationsDenied = change == NotificationsChange.DENIED) }
        }
    }

    /** The latest backup write; each toggle waits for the one before it, so they land in order. */
    private var deviceBackupWrite: Job? = null

    /** The backup page's switch (ADR 0034). Nothing is copied now; the OS decides at its next backup. */
    fun onDeviceBackupToggled(enabled: Boolean) {
        val previous = deviceBackupWrite
        deviceBackupWrite = write {
            previous?.join()
            settings.setDeviceBackupEnabled(enabled)
        }
    }

    fun openSystemNotificationSettings() = systemNotifications.open()

    fun saveKey(raw: String, onSaved: () -> Unit) {
        if (_state.value.keyChecking) return
        _state.update { it.copy(keyChecking = true, keyProblem = null) }
        write {
            // As in Settings' TmdbKeyViewModel: a store failure throws out of save, and the
            // dialog is locked while `keyChecking`, so it is released whatever happens.
            var problem: TmdbKeyProblem? = TmdbKeyProblem.COULD_NOT_CHECK
            try {
                problem = when (tmdbKey.save(raw)) {
                    SaveTmdbKeyResult.SAVED -> null
                    SaveTmdbKeyResult.MALFORMED -> TmdbKeyProblem.MALFORMED
                    SaveTmdbKeyResult.REJECTED -> TmdbKeyProblem.REJECTED
                    SaveTmdbKeyResult.COULD_NOT_CHECK -> TmdbKeyProblem.COULD_NOT_CHECK
                }
            } finally {
                _state.update { it.copy(keyChecking = false, keyProblem = problem) }
            }
            if (problem == null) onSaved()
        }
    }

    fun useMuvissKey(onDone: () -> Unit) {
        write {
            tmdbKey.clear()
            _state.update { it.copy(keyProblem = null) }
            onDone()
        }
    }

    fun dismissKeyProblem() = _state.update { it.copy(keyProblem = null) }
}
