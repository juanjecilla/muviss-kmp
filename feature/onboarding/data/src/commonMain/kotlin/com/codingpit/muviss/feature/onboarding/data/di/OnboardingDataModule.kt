package com.codingpit.muviss.feature.onboarding.data.di

import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.feature.onboarding.api.OnboardingApi
import com.codingpit.muviss.feature.onboarding.data.ApplySystemMotionDefault
import com.codingpit.muviss.feature.onboarding.data.DefaultOnboardingApi
import com.codingpit.muviss.feature.onboarding.data.SqlDelightOnboardingRepository
import com.codingpit.muviss.feature.onboarding.data.notificationNudge
import com.codingpit.muviss.feature.onboarding.domain.CompleteOnboardingUseCase
import com.codingpit.muviss.feature.onboarding.domain.NotificationNudge
import com.codingpit.muviss.feature.onboarding.domain.OnboardingRepository
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Onboarding's bindings. The nudge reads the library and Settings through their `:api`s,
 * resolved lazily by Koin, so this module adds no edge to the construction graph that
 * could close a cycle.
 */
val onboardingDataModule: Module = module {
    single<OnboardingRepository> { SqlDelightOnboardingRepository(get<MuvissDatabase>().appSettingsQueries, get()) }
    factory { CompleteOnboardingUseCase(get()) }
    factory { ApplySystemMotionDefault(get<MuvissDatabase>().appSettingsQueries, get(), get(), get()) }
    single<NotificationNudge> { notificationNudge(get(), get(), get()) }
    single<OnboardingApi> { DefaultOnboardingApi(get(), get(), get()) }
}
