package com.codingpit.muviss.sync

import androidx.compose.runtime.Composable
import com.codingpit.muviss.core.sync.SyncCoordinator

// WorkManager's job is scheduled from `MuvissApplication`, which outlives any composition.
@Composable
@Suppress("UNUSED_PARAMETER")
internal actual fun PlatformSyncTriggers(coordinator: SyncCoordinator) = Unit
