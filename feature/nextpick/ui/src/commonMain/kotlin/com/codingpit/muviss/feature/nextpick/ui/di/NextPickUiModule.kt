package com.codingpit.muviss.feature.nextpick.ui.di

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.feature.nextpick.domain.CompletionPromptStore
import com.codingpit.muviss.feature.nextpick.domain.NextPickActions
import com.codingpit.muviss.feature.nextpick.domain.PickContextSource
import com.codingpit.muviss.feature.nextpick.domain.PickEngine
import com.codingpit.muviss.feature.nextpick.ui.CompletionPromptViewModel
import com.codingpit.muviss.feature.nextpick.ui.PickSessionViewModel
import com.codingpit.muviss.feature.onboarding.api.OnboardingApi
import com.codingpit.muviss.feature.progress.api.ProgressApi
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Next Pick's ViewModels (EPIC 51, #400). The sheet's ViewModel is hosted at the app root (see MuvissApp). */
val nextPickUiModule: Module = module {
    viewModel {
        val context: PickContextSource = get()
        PickSessionViewModel(context = { context.read() }, engine = get<PickEngine>(), actions = get<NextPickActions>())
    }
    viewModel {
        CompletionPromptViewModel(
            completions = get<ProgressApi>().completions,
            onboardingCompleted = get<OnboardingApi>().observeCompleted(),
            store = get<CompletionPromptStore>(),
            clock = get<AppClock>(),
        )
    }
}
