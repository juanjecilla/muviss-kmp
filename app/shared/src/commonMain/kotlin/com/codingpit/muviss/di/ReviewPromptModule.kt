package com.codingpit.muviss.di

import com.codingpit.muviss.core.common.AppVersion
import com.codingpit.muviss.core.common.review.AskForReviewUseCase
import com.codingpit.muviss.core.sync.SyncEngine
import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.review.ReviewPromptOnCompletion
import kotlinx.coroutines.flow.first
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The store-review prompt's wiring (#410). The ledger and the platform prompter are bound
 * elsewhere (`:feature:settings:data`, `commonModule`, and each host's own module); this only
 * assembles the use case and the listener that drives it.
 *
 * The listener takes [ProgressApi.completions] and a probe over [SyncEngine] rather than the
 * engine itself, so its own test does not need a sync graph. The probe reads the engine's
 * standing failure, which the next successful cycle clears.
 */
val reviewPromptModule: Module = module {
    single { AskForReviewUseCase(get(), get(), get(), get<AppVersion>().versionName) }
    single {
        val syncEngine: SyncEngine = get()
        ReviewPromptOnCompletion(
            completions = get<ProgressApi>().completions,
            standingFailure = { syncEngine.observeStatus().first().lastFailure != null },
            ledger = get(),
            clock = get(),
            askForReview = get(),
        )
    }
}
