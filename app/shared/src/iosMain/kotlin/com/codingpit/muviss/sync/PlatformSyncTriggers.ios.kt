package com.codingpit.muviss.sync

import androidx.compose.runtime.Composable
import com.codingpit.muviss.core.sync.SyncCoordinator

// The background refresh is registered by `IosBackgroundRefresh`, not by a composition.
@Composable
@Suppress("UNUSED_PARAMETER")
internal actual fun PlatformSyncTriggers(coordinator: SyncCoordinator) = Unit
