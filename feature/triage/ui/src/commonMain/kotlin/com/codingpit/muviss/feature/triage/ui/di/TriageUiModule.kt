package com.codingpit.muviss.feature.triage.ui.di

import com.codingpit.muviss.feature.triage.ui.SkippedViewModel
import com.codingpit.muviss.feature.triage.ui.SnoozedViewModel
import com.codingpit.muviss.feature.triage.ui.TriageViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val triageUiModule: Module = module {
    viewModelOf(::TriageViewModel)
    viewModelOf(::SkippedViewModel)
    viewModelOf(::SnoozedViewModel)
}
