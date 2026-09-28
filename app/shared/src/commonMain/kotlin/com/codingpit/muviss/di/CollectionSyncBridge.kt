package com.codingpit.muviss.di

import com.codingpit.muviss.core.sync.TitleRefresher
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.models.MediaId
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Joins `:core:sync` to the collection feature — the same shape as
 * [billingSyncBridgeModule], and for the same reason (ADR 0018/0004):
 * `:core:sync` declares [TitleRefresher] as a bare seam so it never learns
 * what `CollectionApi` is, and the collection feature publishes one without
 * knowing sync exists. See `TitleRefresher`'s KDoc and issue #101.
 *
 * Must come after both `syncModule` and `collectionDataModule` in
 * [appModules]: `syncModule` binds [TitleRefresher]'s permissive default, and
 * Koin's last binding wins.
 */
val collectionSyncBridgeModule: Module = module {
    single<TitleRefresher> {
        val collection = get<CollectionApi>()
        TitleRefresher { mediaIds -> collection.refreshTitles(mediaIds.map(MediaId::parse).toSet()) }
    }
}
