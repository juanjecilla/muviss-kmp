package com.codingpit.muviss.feature.settings.data

import com.codingpit.muviss.feature.settings.api.SettingsApi
import com.codingpit.muviss.feature.settings.api.ThemeMode
import com.codingpit.muviss.feature.settings.domain.AppTheme
import com.codingpit.muviss.feature.settings.domain.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** [SettingsApi] over [SettingsRepository], translating domain [AppTheme] to the API's own [ThemeMode]. */
internal class DefaultSettingsApi(private val repository: SettingsRepository) : SettingsApi {

    override fun observeThemeMode(): Flow<ThemeMode> = repository.observeSettings().map { it.theme.toApi() }

    override fun observeNotificationsEnabled(): Flow<Boolean> = repository.observeSettings().map { it.notificationsEnabled }

    private fun AppTheme.toApi(): ThemeMode = when (this) {
        AppTheme.LIGHT -> ThemeMode.LIGHT
        AppTheme.DARK -> ThemeMode.DARK
        AppTheme.SYSTEM -> ThemeMode.SYSTEM
    }
}
