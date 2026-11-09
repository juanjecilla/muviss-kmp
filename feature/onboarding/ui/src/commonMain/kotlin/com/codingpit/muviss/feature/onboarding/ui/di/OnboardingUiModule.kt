package com.codingpit.muviss.feature.onboarding.ui.di

import com.codingpit.muviss.feature.onboarding.ui.OnboardingViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val onboardingUiModule: Module = module {
    // Not `viewModelOf`: that would ask Koin for every constructor parameter, including `canBackUp`, a
    // plain Boolean with a platform default that nothing binds, and a fresh install would crash on the
    // first frame of the introduction. `AppGraphTest` resolves this view model to keep it that way.
    viewModel { OnboardingViewModel(get(), get(), get(), get(), get()) }
}
