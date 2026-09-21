package com.codingpit.muviss.sync

import androidx.compose.runtime.Composable
import com.codingpit.muviss.core.sync.SyncCoordinator

/**
 * The sync triggers that only exist on one platform, installed for as long as
 * the composition lives (EPIC 40, ADR 0021).
 *
 * Only the web has one that belongs here: a tab regaining visibility, or the
 * browser coming back online, asks [SyncCoordinator.onResume]. The others are
 * elsewhere for reasons of where their process lives — Android's WorkManager
 * job in `MuvissApplication`, iOS's background refresh in `IosBackgroundRefresh`,
 * desktop's timer in its `Main.kt` — so their actuals here are empty.
 *
 * This is an `expect` rather than a Koin binding because the listener has to
 * be removed when the composition goes, and because it must reach the very
 * same coordinator instance `AutoSyncOnForeground` uses.
 */
@Composable
internal expect fun PlatformSyncTriggers(coordinator: SyncCoordinator)
