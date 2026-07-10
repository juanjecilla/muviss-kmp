package com.codingpit.muviss.di

import com.codingpit.muviss.core.common.di.commonModule
import com.codingpit.muviss.core.database.di.databaseModule
import com.codingpit.muviss.core.network.di.networkModule
import com.codingpit.muviss.feature.collection.data.di.collectionDataModule
import com.codingpit.muviss.feature.collection.ui.di.collectionUiModule
import com.codingpit.muviss.feature.profile.data.di.profileDataModule
import com.codingpit.muviss.feature.progress.data.di.progressDataModule
import com.codingpit.muviss.feature.progress.ui.di.progressUiModule
import com.codingpit.muviss.feature.search.data.di.searchDataModule
import com.codingpit.muviss.feature.search.ui.di.searchUiModule
import com.codingpit.muviss.feature.settings.data.di.settingsDataModule
import com.codingpit.muviss.feature.settings.ui.di.settingsUiModule
import org.koin.core.module.Module

/** Every Koin module assembled into the app graph. */
val appModules: List<Module> =
    listOf(
        commonModule,
        databaseModule,
        networkModule,
        searchDataModule,
        searchUiModule,
        collectionDataModule,
        collectionUiModule,
        progressDataModule,
        progressUiModule,
        profileDataModule,
        settingsDataModule,
        settingsUiModule,
    )
