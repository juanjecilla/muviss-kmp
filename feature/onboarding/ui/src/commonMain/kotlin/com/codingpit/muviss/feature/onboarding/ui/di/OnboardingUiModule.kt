package com.codingpit.muviss.feature.onboarding.ui.di

import com.codingpit.muviss.feature.onboarding.ui.OnboardingViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val onboardingUiModule: Module = module {
    viewModelOf(::OnboardingViewModel)
}
