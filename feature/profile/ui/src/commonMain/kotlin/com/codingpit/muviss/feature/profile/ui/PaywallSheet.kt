package com.codingpit.muviss.feature.profile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.feature.profile.ui.generated.resources.Res
import com.codingpit.muviss.feature.profile.ui.generated.resources.action_close
import com.codingpit.muviss.feature.profile.ui.generated.resources.paywall_body
import com.codingpit.muviss.feature.profile.ui.generated.resources.paywall_unavailable
import com.codingpit.muviss.feature.profile.ui.generated.resources.sync_title
import org.jetbrains.compose.resources.stringResource

/**
 * Shows the "unlock sync" purchase flow.
 *
 * An interface behind an `expect` factory, rather than a plain composable,
 * because the two ends of this differ per platform and only per platform:
 * RevenueCat's `purchases-kmp-ui` renders a server-driven paywall on Android
 * and iOS, and publishes no artifact at all for jvm/js/wasmJs — a dependency
 * on it from `commonMain` breaks `compileKotlinWasmJs` for every downstream
 * module. This is the same shape, and the same reason, as
 * `feature/settings/ui`'s `DataExporter`.
 *
 * Every actual is [HandRolledPaywall] today; nothing here reaches a store yet
 * (ADR 0018 defers that to a build with a Play Console app behind it). The
 * seam exists now so adding one later is an edit to one platform file rather
 * than a refactor of the profile screen.
 */
interface PaywallPresenter {
    /** Renders the paywall when [visible]; [onDismiss] fires on cancel or completion. */
    @Composable
    fun Paywall(visible: Boolean, onDismiss: () -> Unit)
}

/** Builds the platform [PaywallPresenter]. */
@Composable
expect fun rememberPaywallPresenter(): PaywallPresenter

/**
 * The fallback every target uses until a store SDK is wired in. States what
 * sync does and admits it cannot be bought yet, rather than showing a
 * purchase button that would fail — a dead button reads as a bug, while this
 * reads as a feature that has not shipped.
 */
class HandRolledPaywall : PaywallPresenter {
    @Composable
    override fun Paywall(visible: Boolean, onDismiss: () -> Unit) {
        if (!visible) return
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(Res.string.sync_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(Res.string.paywall_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(Res.string.paywall_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_close)) } },
        )
    }
}
