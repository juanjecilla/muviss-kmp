package com.codingpit.muviss.sync

import androidx.compose.runtime.Composable
import com.codingpit.muviss.core.sync.SyncCoordinator

// Desktop's 15-minute timer lives in `:app:desktopApp`'s `Main.kt`, next to the episode refresh.
@Composable
@Suppress("UNUSED_PARAMETER")
internal actual fun PlatformSyncTriggers(coordinator: SyncCoordinator) = Unit
