package com.codingpit.muviss

import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.codingpit.muviss.app.shared.generated.resources.Res
import com.codingpit.muviss.app.shared.generated.resources.nav_needs_attention
import com.codingpit.muviss.core.sync.SyncAvailability
import com.codingpit.muviss.core.sync.SyncBackend
import com.codingpit.muviss.core.sync.SyncEngine
import com.codingpit.muviss.core.sync.SyncSession
import com.codingpit.muviss.core.sync.SyncStatusSnapshot
import com.codingpit.muviss.core.sync.belongsToAnotherAccountThan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

internal const val NAV_ATTENTION_BADGE_TAG = "nav_attention_badge"

/** The destination whose badge says "an account switch is waiting for you" (#274). */
internal const val ACCOUNT_CHANGE_DESTINATION_KEY = "profile"

/**
 * Whether a sync is stopped waiting for the "which library should this device
 * keep?" choice (#274). The dialog that asks lives on Profile, but the mismatch
 * is just as often found by a foreground sync, `SyncWorker`, iOS background
 * refresh or the desktop timer — all of which stop safely and say nothing.
 * The nav badge is how the person learns of it from any tab; it resolves
 * nothing itself, so the rule that only an explicit choice does still holds.
 */
internal fun accountChangePending(status: Flow<SyncStatusSnapshot>, session: Flow<SyncSession?>): Flow<Boolean> = combine(status, session) { snapshot, current -> snapshot.belongsToAnotherAccountThan(current) }.distinctUntilChanged()

/** False, without touching the sync graph, in a build without sync (ADR 0018). */
@Composable
internal fun rememberAccountChangePending(): Boolean {
    val availability = koinInject<SyncAvailability>()
    if (!availability.isConfigured()) return false
    val engine = koinInject<SyncEngine>()
    val backend = koinInject<SyncBackend>()
    val pending by remember(engine, backend) { accountChangePending(engine.observeStatus(), backend.session) }
        .collectAsState(initial = false)
    return pending
}

@Composable
internal fun NavItemIcon(destination: MuvissDestination, needsAttention: Boolean) {
    if (!needsAttention) {
        Icon(destination.icon, contentDescription = destination.label)
        return
    }
    BadgedBox(badge = { Badge(Modifier.testTag(NAV_ATTENTION_BADGE_TAG)) }) {
        Icon(destination.icon, contentDescription = stringResource(Res.string.nav_needs_attention, destination.label))
    }
}
