@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.settings.ui

import com.codingpit.muviss.core.common.AppVersion
import com.codingpit.muviss.core.common.flags.FeatureFlags
import com.codingpit.muviss.core.common.flags.TriageControlScheme
import com.codingpit.muviss.feature.settings.domain.AppSettings
import com.codingpit.muviss.feature.settings.domain.AppTheme
import com.codingpit.muviss.feature.settings.domain.ExportDataUseCase
import com.codingpit.muviss.feature.settings.domain.ObserveSettingsUseCase
import com.codingpit.muviss.feature.settings.domain.SetCrashReportsEnabledUseCase
import com.codingpit.muviss.feature.settings.domain.SetLanguageUseCase
import com.codingpit.muviss.feature.settings.domain.SetNotificationsEnabledUseCase
import com.codingpit.muviss.feature.settings.domain.SetRegionUseCase
import com.codingpit.muviss.feature.settings.domain.SetThemeUseCase
import com.codingpit.muviss.feature.settings.domain.SettingsActions
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
import kotlin.test.assertNull

private class FakeSettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
    val flow = MutableStateFlow(initial)
    var exportResult: Result<String> = Result.success("""{"collection":[],"progress":[]}""")

    override fun observeSettings(): Flow<AppSettings> = flow
    override suspend fun setTheme(theme: AppTheme) {
        flow.value = flow.value.copy(theme = theme)
    }

    override suspend fun setLanguage(language: String) {
        flow.value = flow.value.copy(language = language)
    }

    override suspend fun setRegion(region: String) {
        flow.value = flow.value.copy(region = region)
    }

    override suspend fun setNotificationsEnabled(enabled: Boolean) {
        flow.value = flow.value.copy(notificationsEnabled = enabled)
    }

    override suspend fun setCrashReportsEnabled(enabled: Boolean) {
        flow.value = flow.value.copy(crashReportsEnabled = enabled)
    }

    override suspend fun exportData(): String = exportResult.getOrThrow()
}

class SettingsViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(repository: FakeSettingsRepository) = SettingsViewModel(
        ObserveSettingsUseCase(repository),
        SettingsActions(
            SetThemeUseCase(repository),
            SetLanguageUseCase(repository),
            SetRegionUseCase(repository),
            SetNotificationsEnabledUseCase(repository),
            SetCrashReportsEnabledUseCase(repository),
            ExportDataUseCase(repository),
        ),
        AppVersion(versionName = "1.0.0", versionCode = 42),
        FakeFeatureFlags(),
    )

    @Test
    fun loads_settings_and_carries_the_app_version_through() = runTest {
        val vm = viewModel(FakeSettingsRepository())
        advanceUntilIdle()

        assertEquals(false, vm.state.value.loading)
        assertEquals(AppTheme.SYSTEM, vm.state.value.settings.theme)
        assertEquals("1.0.0", vm.state.value.appVersion.versionName)
    }

    @Test
    fun onThemeSelected_persists_and_the_new_theme_is_observed() = runTest {
        val repository = FakeSettingsRepository()
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.onThemeSelected(AppTheme.DARK)
        advanceUntilIdle()

        assertEquals(AppTheme.DARK, vm.state.value.settings.theme)
        assertEquals(AppTheme.DARK, repository.flow.value.theme)
    }

    @Test
    fun onLanguageSelected_and_onRegionSelected_persist_independently() = runTest {
        val repository = FakeSettingsRepository()
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.onLanguageSelected("es-ES")
        vm.onRegionSelected("ES")
        advanceUntilIdle()

        assertEquals("es-ES", vm.state.value.settings.language)
        assertEquals("ES", vm.state.value.settings.region)
    }

    @Test
    fun crash_reports_default_to_on_and_the_toggle_persists() = runTest {
        val repository = FakeSettingsRepository()
        val vm = viewModel(repository)
        advanceUntilIdle()
        assertEquals(true, vm.state.value.settings.crashReportsEnabled)

        vm.onCrashReportsToggled(false)
        advanceUntilIdle()

        assertEquals(false, vm.state.value.settings.crashReportsEnabled)
        assertEquals(false, repository.flow.value.crashReportsEnabled)
    }

    @Test
    fun onNotificationsToggled_persists() = runTest {
        val repository = FakeSettingsRepository()
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.onNotificationsToggled(false)
        advanceUntilIdle()

        assertEquals(false, vm.state.value.settings.notificationsEnabled)
    }

    @Test
    fun onAnimationsToggled_persists() = runTest {
        val vm = viewModel(FakeSettingsRepository())
        advanceUntilIdle()

        vm.onAnimationsToggled(false)
        advanceUntilIdle()

        assertEquals(false, vm.state.value.animationsEnabled)
    }

    @Test
    fun onTriageDeckAnimationsToggled_persists_without_touching_the_master() = runTest {
        val vm = viewModel(FakeSettingsRepository())
        advanceUntilIdle()

        vm.onTriageDeckAnimationsToggled(false)
        advanceUntilIdle()

        assertEquals(false, vm.state.value.triageDeckAnimations)
        // The row renders its own stored position; the master governs whether
        // it can be changed, not what it says.
        assertEquals(true, vm.state.value.animationsEnabled)
    }

    @Test
    fun exportData_delivers_the_json_once_then_exportHandled_clears_it() = runTest {
        val repository = FakeSettingsRepository()
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.exportData()
        advanceUntilIdle()

        assertEquals("""{"collection":[],"progress":[]}""", vm.state.value.exportJson)

        vm.exportHandled()
        assertNull(vm.state.value.exportJson)
    }

    @Test
    fun exportData_failure_surfaces_exportError_instead_of_a_json_payload() = runTest {
        val repository = FakeSettingsRepository()
        repository.exportResult = Result.failure(RuntimeException("disk full"))
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.exportData()
        advanceUntilIdle()

        assertNull(vm.state.value.exportJson)
        assertEquals("Something went wrong", vm.state.value.exportError)
    }
}

private class FakeFeatureFlags : FeatureFlags {
    private val scheme = MutableStateFlow(TriageControlScheme.DEFAULT)
    val animations = MutableStateFlow(true)
    val deckAnimations = MutableStateFlow(true)

    override val triageControlScheme: Flow<TriageControlScheme> = scheme
    override val animationsEnabled: Flow<Boolean> = animations
    override val triageDeckAnimations: Flow<Boolean> = deckAnimations
    val autoSync = MutableStateFlow(false)
    override val syncAutomatically: Flow<Boolean> = autoSync

    override suspend fun setSyncAutomatically(enabled: Boolean) {
        autoSync.value = enabled
    }

    override suspend fun setTriageControlScheme(scheme: TriageControlScheme) {
        this.scheme.value = scheme
    }

    override suspend fun setAnimationsEnabled(enabled: Boolean) {
        animations.value = enabled
    }

    override suspend fun setTriageDeckAnimations(enabled: Boolean) {
        deckAnimations.value = enabled
    }
}
