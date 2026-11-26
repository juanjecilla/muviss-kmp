package com.codingpit.muviss.di

import com.codingpit.muviss.core.sync.AccountLocalDataEraser
import com.codingpit.muviss.feature.settings.domain.DeleteAllDataUseCase
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Joins `:core:sync` to the settings feature's "Delete all data" (ADR 0019,
 * "Deleting the account"). The same shape as [billingSyncBridgeModule] and
 * [collectionSyncBridgeModule], for the same reason: `:core:sync` declares
 * [AccountLocalDataEraser] as a bare seam, and settings publishes its eraser
 * without knowing sync exists.
 *
 * It delegates to [DeleteAllDataUseCase] rather than the eraser, so deleting an
 * account and "Delete all data" share one entry point and cannot drift apart.
 *
 * Must come after `syncModule` in [appModules]: that module binds the no-op
 * default, and Koin's last binding wins.
 */
val accountDeletionBridgeModule: Module = module {
    // Resolved per call, not when the eraser is built: SyncEngine takes this seam, and
    // DeleteAllDataUseCase reaches SyncEngine through settings, so an eager get() here is a
    // construction cycle (StackOverflowError on the desktop launch, #279).
    single<AccountLocalDataEraser> {
        AccountLocalDataEraser { get<DeleteAllDataUseCase>()() }
    }
}
