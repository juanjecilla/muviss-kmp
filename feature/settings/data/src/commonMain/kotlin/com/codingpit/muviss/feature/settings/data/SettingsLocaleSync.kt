package com.codingpit.muviss.feature.settings.data

import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.network.MutableMetadataLocale
import com.codingpit.muviss.feature.settings.domain.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Keeps `:core:network`'s [MutableMetadataLocale] singleton in sync with the
 * user's saved language/region so every TMDB request reflects the latest
 * settings without an app restart. Bound `createdAtStart()` in
 * [com.codingpit.muviss.feature.settings.data.di.settingsDataModule] so this
 * subscription starts as soon as Koin wires the app graph, not lazily on
 * first `get()`.
 */
class SettingsLocaleSync(
    settingsRepository: SettingsRepository,
    mutableLocale: MutableMetadataLocale,
    dispatchers: AppDispatchers,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)

    init {
        settingsRepository.observeSettings()
            .onEach { settings ->
                mutableLocale.language = settings.language
                mutableLocale.region = settings.region
            }
            .launchIn(scope)
    }
}
