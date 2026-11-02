package com.codingpit.muviss.feature.onboarding.data

import com.codingpit.muviss.core.common.notifications.platformCanNotify
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.onboarding.api.NotificationNudgeResult
import com.codingpit.muviss.feature.onboarding.api.OnboardingApi
import com.codingpit.muviss.feature.onboarding.domain.CompleteOnboardingUseCase
import com.codingpit.muviss.feature.onboarding.domain.NotificationNudge
import com.codingpit.muviss.feature.onboarding.domain.OnboardingRepository
import com.codingpit.muviss.feature.settings.api.NotificationsChange
import com.codingpit.muviss.feature.settings.api.SettingsApi
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** [OnboardingApi] over the repository, with the nudge fed by the library and Settings through their own `:api`s. */
internal class DefaultOnboardingApi(
    private val repository: OnboardingRepository,
    private val completeOnboarding: CompleteOnboardingUseCase,
    private val nudge: NotificationNudge,
) : OnboardingApi {

    override fun observeCompleted(): Flow<Boolean> = repository.observeCompleted()

    override suspend fun markCompleted() = completeOnboarding()

    override fun observeNotificationNudge(mediaId: MediaId): Flow<Boolean> = nudge.observe(mediaId)

    override suspend fun acceptNotificationNudge(): NotificationNudgeResult = nudge.accept()

    override suspend fun dismissNotificationNudge() = nudge.dismiss()
}

/** Builds the nudge from the two peers it reads; kept out of the Koin module so a test can build the same thing. */
internal fun notificationNudge(collection: CollectionApi, settings: SettingsApi, repository: OnboardingRepository): NotificationNudge = NotificationNudge(
    tvShows = collection.observeSummaries().map { all -> all.filter { it.mediaId.type == MediaType.TV }.map { it.mediaId } },
    notificationsEnabled = settings.observeNotificationsEnabled(),
    repository = repository,
    canNotify = platformCanNotify,
    turnOn = { settings.setNotificationsEnabled(true) == NotificationsChange.ENABLED },
)
