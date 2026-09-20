package com.codingpit.muviss.sync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.codingpit.muviss.core.sync.SyncCoordinator
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.events.Event

/**
 * A tab that becomes visible again, or a browser that comes back online, asks
 * for a sync ([SyncCoordinator.onResume], sharing the foreground skip window so
 * a tab that flickers does not sync each time).
 *
 * Inert today: web has no sign-in yet (#46, EPIC 32), so the engine answers
 * `NotSignedIn` to every one of these. It is wired now so that the day sign-in
 * exists this is not the piece that was forgotten, and what it decides is
 * tested through the shared coordinator (`SyncCoordinatorTest`) — the listener
 * itself is only checked in a real browser (two tabs, see the manual checklist
 * on #86).
 */
@Composable
internal actual fun PlatformSyncTriggers(coordinator: SyncCoordinator) {
    DisposableEffect(coordinator) {
        val onVisibilityChange: (Event) -> Unit = { if (isDocumentVisible()) coordinator.onResume() }
        val onOnline: (Event) -> Unit = { coordinator.onResume() }
        document.addEventListener("visibilitychange", onVisibilityChange)
        window.addEventListener("online", onOnline)
        onDispose {
            document.removeEventListener("visibilitychange", onVisibilityChange)
            window.removeEventListener("online", onOnline)
        }
    }
}
