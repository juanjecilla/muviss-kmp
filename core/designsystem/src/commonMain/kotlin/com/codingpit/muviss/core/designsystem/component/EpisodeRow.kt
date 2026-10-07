package com.codingpit.muviss.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.designsystem.generated.resources.Res
import com.codingpit.muviss.core.designsystem.generated.resources.a11y_mark_unwatched
import com.codingpit.muviss.core.designsystem.generated.resources.a11y_mark_watched
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import org.jetbrains.compose.resources.stringResource

/**
 * Episode list row: 16:9 still thumb, title + "S2 · E1" subtitle, trailing
 * seen-toggle. States per the design doc — unseen (outline circle), seen
 * (sage check circle), next-up (2dp amber left rule + raised background).
 * Toggling to seen plays the tick animation (scale 0.6→1.15→1.0) with a
 * haptic tick; undo/snackbar behavior belongs to the caller.
 *
 * [secondaryActionLabel]/[onSecondaryAction] render a small text action
 * before the toggle (e.g. Detail's "Catch up" = mark-previous-seen).
 */
@Composable
fun EpisodeRow(
    title: String,
    subtitle: String,
    seen: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    stillUrl: String? = null,
    nextUp: Boolean = false,
    onClick: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    val ruleColor = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .then(
                if (nextUp) {
                    Modifier
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .drawBehind { drawRect(color = ruleColor, size = Size(2.dp.toPx(), size.height)) }
                } else {
                    Modifier
                },
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(8.dp),
    ) {
        Box(
            Modifier
                .width(52.dp)
                .aspectRatio(16f / 9f)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            PosterImage(url = stillUrl, title = "", modifier = Modifier, size = PosterSize.Thumbnail)
        }
        Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (seen) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (secondaryActionLabel != null && onSecondaryAction != null) {
            TextButton(onClick = onSecondaryAction) {
                Text(secondaryActionLabel, style = MaterialTheme.typography.labelMedium)
            }
        }
        SeenToggle(seen = seen, onToggle = onToggle, label = "$title $subtitle")
    }
}

@Composable
private fun SeenToggle(
    seen: Boolean,
    onToggle: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val scale = remember { Animatable(1f) }
    // Skip the initial composition — the tick animation and haptic should only
    // fire on an actual toggle, not when an already-seen row scrolls in.
    val firstComposition = remember { mutableStateOf(true) }
    LaunchedEffect(seen) {
        if (firstComposition.value) {
            firstComposition.value = false
            return@LaunchedEffect
        }
        if (seen) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            scale.snapTo(0.6f)
            scale.animateTo(
                targetValue = 1f,
                animationSpec = keyframes {
                    durationMillis = 200
                    1.15f at 120
                    1f at 200
                },
            )
        }
    }
    val description = stringResource(if (seen) Res.string.a11y_mark_unwatched else Res.string.a11y_mark_watched, label)
    IconButton(
        onClick = onToggle,
        modifier = modifier.semantics {
            contentDescription = description
        },
    ) {
        if (seen) {
            Icon(
                MuvissIcons.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(24.dp).graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                },
            )
        } else {
            Box(
                Modifier
                    .size(24.dp)
                    .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape),
            )
        }
    }
}
