@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.settings.ui

import com.codingpit.muviss.core.common.AppVersion
import com.codingpit.muviss.core.common.flags.FeatureFlags
import com.codingpit.muviss.core.common.flags.SnoozePeriod
import com.codingpit.muviss.core.common.flags.SnoozePlacement
import com.codingpit.muviss.core.common.flags.TriageControlScheme
import com.codingpit.muviss.core.common.notifications.NotificationPermissionRequester
import com.codingpit.muviss.core.common.remoteconfig.DefaultsRemoteConfig
import com.codingpit.muviss.core.common.remoteconfig.RemoteConfig
import com.codingpit.muviss.core.common.remoteconfig.RemoteValues
import com.codingpit.muviss.core.designsystem.text.resolveAsync
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.feature.settings.domain.AppSettings
import com.codingpit.muviss.feature.settings.domain.AppTheme
import com.codingpit.muviss.feature.settings.domain.DeleteAllDataUseCase
import com.codingpit.muviss.feature.settings.domain.ExportDataUseCase
import com.codingpit.muviss.feature.settings.domain.LocalDataEraser
import com.codingpit.muviss.feature.settings.domain.MutedGenreEntry
import com.codingpit.muviss.feature.settings.domain.MutedGenresActions
import com.codingpit.muviss.feature.settings.domain.ObserveSettingsUseCase
import com.codingpit.muviss.feature.settings.domain.SetCrashReportsEnabledUseCase
import com.codingpit.muviss.feature.settings.domain.SetDeviceBackupEnabledUseCase
import com.codingpit.muviss.feature.settings.domain.SetLanguageUseCase
import com.codingpit.muviss.feature.settings.domain.SetNotificationsEnabledUseCase
import com.codingpit.muviss.feature.settings.domain.SetRegionUseCase
import com.codingpit.muviss.feature.settings.domain.SetThemeUseCase
import com.codingpit.muviss.feature.settings.domain.SettingsActions
import com.codingpit.muviss.feature.settings.domain.SettingsRepository
import com.codingpit.muviss.models.GenreKey
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
import kotlin.test.assertTrue

private class FakeSettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
    val flow = MutableStateFlow(initial)
    var exportResult: Result<String> = Result.success("""{"collection":[],"progress":[]}""")

    var failure: Throwable? = null

    override fun observeSettings(): Flow<AppSettings> = failure?.let { kotlinx.coroutines.flow.flow { throw it } } ?: flow
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

    override suspend fun setDeviceBackupEnabled(enabled: Boolean) {
        flow.value = flow.value.copy(deviceBackupEnabled = enabled)
    }

    override suspend fun setGenreMuted(key: GenreKey, name: String, muted: Boolean) {
        val kept = flow.value.mutedGenres.filterNot { it.key == key }
        flow.value = flow.value.copy(mutedGenres = if (muted) kept + MutedGenreEntry(key, name) else kept)
    }

    override suspend fun setMutedGenresInSearch(enabled: Boolean) {
        flow.value = flow.value.copy(mutedGenresInSearch = enabled)
    }

    override suspend fun exportData(): String = exportResult.getOrThrow()
}

private class FakeEraser : LocalDataEraser {
    var calls = 0
        private set

    override suspend fun deleteAllData() {
        calls++
    }
}

private class FakePermission(var answer: Boolean = true) : NotificationPermissionRequester {
    var asked = 0
        private set

    override suspend fun request(): Boolean {
        asked++
        return answer
    }
}

class SettingsViewModelTest {

    private val eraser = FakeEraser()
    private val permission = FakePermission()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        repository: FakeSettingsRepository,
        remote: RemoteConfig = DefaultsRemoteConfig(RemoteValues(byokEnabled = false)),
    ) = SettingsViewModel(
        settingsObservations(ObserveSettingsUseCase(repository), remote),
        SettingsActions(
            SetThemeUseCase(repository),
            SetLanguageUseCase(repository),
            SetRegionUseCase(repository),
            SetNotificationsEnabledUseCase(repository, permission),
            SetCrashReportsEnabledUseCase(repository),
            MutedGenresActions(repository),
            ExportDataUseCase(repository),
            SetDeviceBackupEnabledUseCase(repository),
        ),
        AppVersion(versionName = "1.0.0", versionCode = 42),
        FakeFeatureFlags(),
        DeleteAllDataUseCase(eraser),
        FakeClock(1_791_374_400_000L), // 2026-10-07T12:00:00Z
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
    fun onNotificationsToggled_on_asks_the_os_and_persists_when_it_agrees() = runTest {
        val repository = FakeSettingsRepository()
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.onNotificationsToggled(true)
        advanceUntilIdle()

        assertEquals(1, permission.asked)
        assertEquals(true, vm.state.value.settings.notificationsEnabled)
        assertEquals(false, vm.state.value.notificationsDenied)
    }

    @Test
    fun onNotificationsToggled_on_stores_nothing_when_the_os_refuses() = runTest {
        permission.answer = false
        val repository = FakeSettingsRepository()
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.onNotificationsToggled(true)
        advanceUntilIdle()

        assertEquals(false, repository.flow.value.notificationsEnabled, "a switch must never read on for an alert that cannot arrive")
        assertEquals(true, vm.state.value.notificationsDenied, "the screen needs to say why the switch snapped back")
    }

    @Test
    fun onNotificationsToggled_off_never_asks_the_os_and_clears_a_denial() = runTest {
        permission.answer = false
        val repository = FakeSettingsRepository()
        val vm = viewModel(repository)
        advanceUntilIdle()
        vm.onNotificationsToggled(true)
        advanceUntilIdle()

        vm.onNotificationsToggled(false)
        advanceUntilIdle()

        assertEquals(1, permission.asked, "only the first, enabling, toggle asks")
        assertEquals(false, vm.state.value.notificationsDenied)
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
    fun onTickAnimationsToggled_persists_without_touching_the_master() = runTest {
        val vm = viewModel(FakeSettingsRepository())
        advanceUntilIdle()

        vm.onTickAnimationsToggled(false)
        advanceUntilIdle()

        assertEquals(false, vm.state.value.tickAnimations)
        assertEquals(true, vm.state.value.animationsEnabled)
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
        assertEquals("Something went wrong", vm.state.value.exportError?.resolveAsync())
    }

    @Test
    fun retry_after_a_failed_settings_load_resubscribes() = runTest {
        val repository = FakeSettingsRepository().apply { failure = IllegalStateException("db locked") }
        val vm = viewModel(repository)
        advanceUntilIdle()
        kotlin.test.assertNotNull(vm.state.value.error)

        repository.failure = null
        vm.retry()
        advanceUntilIdle()

        kotlin.test.assertNull(vm.state.value.error)
        kotlin.test.assertFalse(vm.state.value.loading)
    }

    @Test
    fun retry_after_a_failed_settings_load_brings_the_feature_request_link_back() = runTest {
        val remote = DefaultsRemoteConfig(RemoteValues(byokEnabled = false, featureRequestUrl = "https://tally.so/r/abc"))
        val repository = FakeSettingsRepository().apply { failure = IllegalStateException("db locked") }
        val vm = viewModel(repository, remote)
        advanceUntilIdle()
        // The link is combined with the settings, so the failure ended it too.
        assertNull(vm.state.value.featureRequestUrl)

        repository.failure = null
        vm.retry()
        advanceUntilIdle()

        assertTrue(vm.state.value.featureRequestUrl.orEmpty().startsWith("https://tally.so/r/abc?version=1.0.0"))
    }

    @Test
    fun the_export_is_offered_under_a_dated_name() = runTest {
        val vm = viewModel(FakeSettingsRepository())
        advanceUntilIdle()

        vm.exportData()
        advanceUntilIdle()

        assertEquals("muviss-backup-2026-10-07.json", vm.state.value.exportFileName)
    }

    @Test
    fun deleting_everything_takes_the_confirmation_not_just_the_row() = runTest {
        val vm = viewModel(FakeSettingsRepository())
        advanceUntilIdle()

        vm.onDeleteAllRequested()
        advanceUntilIdle()
        assertTrue(vm.state.value.confirmingDeleteAll)
        assertEquals(0, eraser.calls, "opening the confirmation must not delete anything")

        vm.onDeleteAllConfirmed()
        advanceUntilIdle()
        assertEquals(1, eraser.calls)
        assertTrue(vm.state.value.deletedAll)
    }

    @Test
    fun dismissing_the_confirmation_deletes_nothing() = runTest {
        val vm = viewModel(FakeSettingsRepository())
        vm.onDeleteAllRequested()
        vm.onDeleteAllDismissed()
        advanceUntilIdle()

        assertEquals(0, eraser.calls)
        assertEquals(false, vm.state.value.confirmingDeleteAll)
    }
}

private class FakeFeatureFlags : FeatureFlags {
    private val scheme = MutableStateFlow(TriageControlScheme.DEFAULT)
    val animations = MutableStateFlow(true)
    val deckAnimations = MutableStateFlow(true)
    val tickAnims = MutableStateFlow(true)

    override val triageControlScheme: Flow<TriageControlScheme> = scheme
    override val animationsEnabled: Flow<Boolean> = animations
    override val triageDeckAnimations: Flow<Boolean> = deckAnimations
    override val tickAnimations: Flow<Boolean> = tickAnims
    val autoSync = MutableStateFlow(false)
    override val syncAutomatically: Flow<Boolean> = autoSync
    override val byokEnabled: Flow<Boolean> = MutableStateFlow(false)
    val snoozePeriod = MutableStateFlow(SnoozePeriod.DEFAULT)
    val snoozePlacement = MutableStateFlow(SnoozePlacement.DEFAULT)
    override val triageSnoozePeriod: Flow<SnoozePeriod> = snoozePeriod
    override val triageSnoozePlacement: Flow<SnoozePlacement> = snoozePlacement

    override suspend fun setTriageSnoozePeriod(period: SnoozePeriod) {
        snoozePeriod.value = period
    }

    override suspend fun setTriageSnoozePlacement(placement: SnoozePlacement) {
        snoozePlacement.value = placement
    }

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

    override suspend fun setTickAnimations(enabled: Boolean) {
        tickAnims.value = enabled
    }
}
