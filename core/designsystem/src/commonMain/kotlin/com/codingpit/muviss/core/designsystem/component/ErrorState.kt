package com.codingpit.muviss.core.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.designsystem.generated.resources.Res
import com.codingpit.muviss.core.designsystem.generated.resources.action_open_settings
import com.codingpit.muviss.core.designsystem.generated.resources.action_retry
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.core.designsystem.text.OwnKeyRejectedText
import com.codingpit.muviss.core.designsystem.text.UiText
import com.codingpit.muviss.core.designsystem.text.resolve
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import org.jetbrains.compose.resources.stringResource

/**
 * Where the "TMDB rejected your key" error sends the person to fix it (#321):
 * the app shell provides a navigation to Settings, and a screen with no way to
 * navigate (a test, a preview) leaves it null, so the error shows no button.
 */
val LocalOpenTmdbKeySettings = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * An [ErrorState] for a view model's [UiText]. A rejected Own TMDB Key also gets
 * an "Open Settings" button, because retrying cannot succeed until the key is
 * changed; every other error is the plain retry state.
 */
@Composable
fun ErrorState(
    message: UiText,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ErrorState(
        message = message.resolve(),
        onRetry = onRetry,
        modifier = modifier,
        onOpenSettings = if (message == OwnKeyRejectedText) LocalOpenTmdbKeySettings.current else null,
    )
}

/** Centered error state with a retry button, and an Open Settings one when [onOpenSettings] is given. */
@Composable
fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenSettings: (() -> Unit)? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth().padding(vertical = MuvissSpacing.huge, horizontal = MuvissSpacing.xl),
    ) {
        Icon(
            MuvissIcons.Error,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(MuvissSpacing.l))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(MuvissSpacing.l))
        if (onOpenSettings != null) {
            Button(onClick = onOpenSettings) { Text(stringResource(Res.string.action_open_settings)) }
            Spacer(Modifier.height(MuvissSpacing.s))
        }
        OutlinedButton(onClick = onRetry) { Text(stringResource(Res.string.action_retry)) }
    }
}
