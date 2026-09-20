@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.settings.data

import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.network.MutableMetadataLocale
import com.codingpit.muviss.feature.settings.domain.AppSettings
import com.codingpit.muviss.feature.settings.domain.AppTheme
import com.codingpit.muviss.feature.settings.domain.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class FakeSettingsRepository(initial: AppSettings) : SettingsRepository {
    val flow = MutableStateFlow(initial)
    override fun observeSettings(): Flow<AppSettings> = flow
    override suspend fun setTheme(theme: AppTheme) = error("not used")
    override suspend fun setLanguage(language: String) = error("not used")
    override suspend fun setRegion(region: String) = error("not used")
    override suspend fun setNotificationsEnabled(enabled: Boolean) = error("not used")
    override suspend fun setCrashReportsEnabled(enabled: Boolean) = error("not used")
    override suspend fun exportData(): String = error("not used")
}

private class TestDispatchers(private val dispatcher: kotlinx.coroutines.CoroutineDispatcher) : AppDispatchers {
    override val default = dispatcher
    override val io = dispatcher
}

/**
 * Verifies the seam settings backs into `:core:network`'s `MetadataLocale`
 * (see `MutableMetadataLocale`'s doc): a language/region change reaches the
 * TMDB-facing locale without anything re-reading it needing to know settings
 * exist at all.
 */
class SettingsLocaleSyncTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun starts_the_mutable_locale_at_the_current_settings() = runTest {
        val repository = FakeSettingsRepository(AppSettings(language = "es-ES", region = "ES"))
        val locale = MutableMetadataLocale()

        SettingsLocaleSync(repository, locale, TestDispatchers(UnconfinedTestDispatcher(testScheduler)))
        advanceUntilIdle()

        assertEquals("es-ES", locale.language)
        assertEquals("ES", locale.region)
    }

    @Test
    fun locale_change_propagates_live_to_the_mutable_locale() = runTest {
        val repository = FakeSettingsRepository(AppSettings())
        val locale = MutableMetadataLocale()

        SettingsLocaleSync(repository, locale, TestDispatchers(UnconfinedTestDispatcher(testScheduler)))
        advanceUntilIdle()
        assertEquals("en-US", locale.language)

        repository.flow.value = repository.flow.value.copy(language = "fr-FR", region = "FR")
        advanceUntilIdle()

        assertEquals("fr-FR", locale.language)
        assertEquals("FR", locale.region)
    }
}
