package com.codingpit.muviss.feature.onboarding.ui

import com.codingpit.muviss.core.common.notifications.SystemNotificationSettings
import com.codingpit.muviss.feature.onboarding.api.NotificationNudgeResult
import com.codingpit.muviss.feature.onboarding.api.OnboardingApi
import com.codingpit.muviss.feature.settings.api.GenreMuting
import com.codingpit.muviss.feature.settings.api.NotificationsChange
import com.codingpit.muviss.feature.settings.api.SaveTmdbKeyResult
import com.codingpit.muviss.feature.settings.api.SettingsApi
import com.codingpit.muviss.feature.settings.api.ThemeMode
import com.codingpit.muviss.feature.settings.api.TmdbDataSource
import com.codingpit.muviss.feature.settings.api.TmdbKeyApi
import com.codingpit.muviss.models.GenreKey
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.MutedGenres
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

internal class FakeOnboardingApi : OnboardingApi {
    val completed = MutableStateFlow(false)
    var completions = 0
        private set

    override fun observeCompleted(): Flow<Boolean> = completed

    override suspend fun markCompleted() {
        completions++
        completed.value = true
    }

    override fun observeNotificationNudge(mediaId: MediaId): Flow<Boolean> = flowOf(false)

    override suspend fun acceptNotificationNudge(): NotificationNudgeResult = NotificationNudgeResult.ENABLED

    override suspend fun dismissNotificationNudge() = Unit
}

internal class FakeSettingsApi : SettingsApi {
    val notifications = MutableStateFlow(false)
    val deviceBackup = MutableStateFlow(false)
    var osAnswer = true

    override fun observeThemeMode(): Flow<ThemeMode> = flowOf(ThemeMode.SYSTEM)

    override fun observeNotificationsEnabled(): Flow<Boolean> = notifications

    override fun observeCrashReportsEnabled(): Flow<Boolean> = flowOf(true)
    override fun observeDeviceBackupEnabled(): Flow<Boolean> = deviceBackup

    override suspend fun setDeviceBackupEnabled(enabled: Boolean) {
        deviceBackup.value = enabled
    }

    /** What Onboarding's genre page toggles; starts as the shipped default. */
    val muted = MutableStateFlow(MutedGenres(setOf("10767", "10763", "10764").mapTo(linkedSetOf()) { GenreKey(MediaType.TV, it) }))
    val showMuted = MutableStateFlow(false)

    override fun observeMuting(): Flow<GenreMuting> = combine(muted, showMuted) { configured, show -> GenreMuting(configured, show) }

    /** Posters the best-effort strip gets per genre id; a genre with no entry returns none. */
    val examples = mutableMapOf<String, List<MediaSummary>>()
    var examplesFailure: Throwable? = null

    override suspend fun examplesFor(key: GenreKey, limit: Int): List<MediaSummary> {
        examplesFailure?.let { throw it }
        return examples[key.id].orEmpty().take(limit)
    }

    override fun setShowMutedThisSession(show: Boolean) {
        showMuted.value = show
    }

    override suspend fun setGenreMuted(key: GenreKey, name: String, muted: Boolean) {
        this.muted.value = MutedGenres(if (muted) this.muted.value.keys + key else this.muted.value.keys - key)
    }

    override suspend fun setNotificationsEnabled(enabled: Boolean): NotificationsChange = when {
        !enabled -> NotificationsChange.DISABLED.also { notifications.value = false }
        osAnswer -> NotificationsChange.ENABLED.also { notifications.value = true }
        else -> NotificationsChange.DENIED
    }
}

internal class FakeTmdbKeyApi : TmdbKeyApi {
    val source = MutableStateFlow(TmdbDataSource.MUVISS)
    var result = SaveTmdbKeyResult.SAVED
    val saved = mutableListOf<String>()
    var failure: Throwable? = null

    override fun observeSource(): Flow<TmdbDataSource> = source

    override suspend fun save(raw: String): SaveTmdbKeyResult {
        saved += raw
        failure?.let { throw it }
        if (result == SaveTmdbKeyResult.SAVED) source.value = TmdbDataSource.YOURS
        return result
    }

    override suspend fun clear() {
        source.value = TmdbDataSource.MUVISS
    }
}

internal class FakeSystemNotifications : SystemNotificationSettings {
    var opened = 0
        private set

    override fun blocked(): Boolean = false

    override fun open() {
        opened++
    }
}
