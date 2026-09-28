package com.codingpit.muviss.feature.settings.data

import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.locale.SystemLocale
import com.codingpit.muviss.core.network.MutableMetadataLocale
import com.codingpit.muviss.feature.settings.domain.SettingsRepository
import com.codingpit.muviss.feature.settings.domain.SupportedLocales
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
 *
 * `settings.language` may be [SupportedLocales.SYSTEM_DEFAULT_LANGUAGE] (see
 * that constant's KDoc, issue #136) — [SupportedLocales.resolveLanguage]
 * turns that into a concrete TMDB language from [systemLocale], read live on
 * every emission, before it reaches [MutableMetadataLocale]. `region` has no
 * such sentinel: it stays a straight passthrough.
 */
class SettingsLocaleSync(
    settingsRepository: SettingsRepository,
    mutableLocale: MutableMetadataLocale,
    dispatchers: AppDispatchers,
    systemLocale: SystemLocale,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)

    init {
        settingsRepository.observeSettings()
            .onEach { settings ->
                mutableLocale.language = SupportedLocales.resolveLanguage(settings.language, systemLocale.languageTag)
                mutableLocale.region = settings.region
            }
            .launchIn(scope)
    }
}
