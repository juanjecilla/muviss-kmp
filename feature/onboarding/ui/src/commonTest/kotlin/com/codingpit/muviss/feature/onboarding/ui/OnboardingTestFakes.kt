package com.codingpit.muviss.feature.onboarding.ui

import com.codingpit.muviss.core.common.notifications.SystemNotificationSettings
import com.codingpit.muviss.feature.onboarding.api.NotificationNudgeResult
import com.codingpit.muviss.feature.onboarding.api.OnboardingApi
import com.codingpit.muviss.feature.settings.api.NotificationsChange
import com.codingpit.muviss.feature.settings.api.SaveTmdbKeyResult
import com.codingpit.muviss.feature.settings.api.SettingsApi
import com.codingpit.muviss.feature.settings.api.ThemeMode
import com.codingpit.muviss.feature.settings.api.TmdbDataSource
import com.codingpit.muviss.feature.settings.api.TmdbKeyApi
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
    var osAnswer = true

    override fun observeThemeMode(): Flow<ThemeMode> = flowOf(ThemeMode.SYSTEM)

    override fun observeNotificationsEnabled(): Flow<Boolean> = notifications

    override fun observeCrashReportsEnabled(): Flow<Boolean> = flowOf(true)

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

    override fun observeSource(): Flow<TmdbDataSource> = source

    override suspend fun save(raw: String): SaveTmdbKeyResult {
        saved += raw
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
