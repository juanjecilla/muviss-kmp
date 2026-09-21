package com.codingpit.muviss.di

import com.codingpit.muviss.core.billing.di.billingModule
import com.codingpit.muviss.core.common.di.commonModule
import com.codingpit.muviss.core.database.di.databaseModule
import com.codingpit.muviss.core.network.di.networkModule
import com.codingpit.muviss.core.sync.di.syncModule
import com.codingpit.muviss.feature.collection.data.di.collectionDataModule
import com.codingpit.muviss.feature.collection.ui.di.collectionUiModule
import com.codingpit.muviss.feature.cowatch.data.di.coWatchDataModule
import com.codingpit.muviss.feature.cowatch.ui.di.coWatchUiModule
import com.codingpit.muviss.feature.profile.data.di.profileDataModule
import com.codingpit.muviss.feature.profile.ui.di.profileUiModule
import com.codingpit.muviss.feature.progress.data.di.progressDataModule
import com.codingpit.muviss.feature.progress.ui.di.progressUiModule
import com.codingpit.muviss.feature.search.data.di.searchDataModule
import com.codingpit.muviss.feature.search.ui.di.searchUiModule
import com.codingpit.muviss.feature.settings.data.di.settingsDataModule
import com.codingpit.muviss.feature.settings.ui.di.settingsUiModule
import com.codingpit.muviss.feature.triage.data.di.triageDataModule
import com.codingpit.muviss.feature.triage.ui.di.triageUiModule
import org.koin.core.module.Module

/** Every Koin module assembled into the app graph. */
val appModules: List<Module> =
    listOf(
        commonModule,
        databaseModule,
        networkModule,
        billingModule,
        syncModule,
        // After syncModule: it overrides that module's permissive default
        // EntitlementGate, and Koin's last binding wins.
        billingSyncBridgeModule,
        searchDataModule,
        searchUiModule,
        collectionDataModule,
        collectionUiModule,
        progressDataModule,
        progressUiModule,
        profileDataModule,
        profileUiModule,
        settingsDataModule,
        settingsUiModule,
        triageDataModule,
        triageUiModule,
        coWatchDataModule,
        coWatchUiModule,
    )
