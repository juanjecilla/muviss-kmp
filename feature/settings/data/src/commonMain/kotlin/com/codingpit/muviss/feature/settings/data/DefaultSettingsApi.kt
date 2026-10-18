package com.codingpit.muviss.feature.settings.data

import com.codingpit.muviss.feature.settings.api.GenreMuting
import com.codingpit.muviss.feature.settings.api.NotificationsChange
import com.codingpit.muviss.feature.settings.api.SettingsApi
import com.codingpit.muviss.feature.settings.api.ThemeMode
import com.codingpit.muviss.feature.settings.domain.AppTheme
import com.codingpit.muviss.feature.settings.domain.GenreCatalog
import com.codingpit.muviss.feature.settings.domain.MutedGenresCodec
import com.codingpit.muviss.feature.settings.domain.SetNotificationsEnabledUseCase
import com.codingpit.muviss.feature.settings.domain.SettingsRepository
import com.codingpit.muviss.models.GenreKey
import com.codingpit.muviss.models.MediaSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** [SettingsApi] over [SettingsRepository], translating domain [AppTheme] to the API's own [ThemeMode]. */
internal class DefaultSettingsApi(
    private val repository: SettingsRepository,
    private val setNotifications: SetNotificationsEnabledUseCase,
    private val session: MutedGenresSession,
    private val genres: GenreCatalog,
) : SettingsApi {

    override fun observeThemeMode(): Flow<ThemeMode> = repository.observeSettings().map { it.theme.toApi() }

    override fun observeNotificationsEnabled(): Flow<Boolean> = repository.observeSettings().map { it.notificationsEnabled }

    override fun observeCrashReportsEnabled(): Flow<Boolean> = repository.observeSettings().map { it.crashReportsEnabled }

    override fun observeMuting(): Flow<GenreMuting> = combine(repository.observeSettings(), session.showMuted) { settings, showMuted ->
        GenreMuting(
            configured = MutedGenresCodec.toMutedGenres(settings.mutedGenres),
            showMuted = showMuted,
            inSearch = settings.mutedGenresInSearch,
        )
    }.distinctUntilChanged()

    override fun setShowMutedThisSession(show: Boolean) = session.set(show)

    override suspend fun setGenreMuted(key: GenreKey, name: String, muted: Boolean) = repository.setGenreMuted(key, name, muted)

    override suspend fun examplesFor(key: GenreKey, limit: Int): List<MediaSummary> = genres.examples(key.type, key.id, limit).getOrDefault(emptyList())

    override suspend fun setNotificationsEnabled(enabled: Boolean): NotificationsChange = setNotifications(enabled)

    private fun AppTheme.toApi(): ThemeMode = when (this) {
        AppTheme.LIGHT -> ThemeMode.LIGHT
        AppTheme.DARK -> ThemeMode.DARK
        AppTheme.SYSTEM -> ThemeMode.SYSTEM
    }
}
