package com.codingpit.muviss.core.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The one poster card. 2:3 poster with optional rating badge (top-right),
 * watched-progress strip (bottom, sage) and selection ring; pressed state
 * scales to 0.97 per the motion spec. Poster grids, carousels and
 * more-like-this rows all use this — don't hand-roll another Surface+poster.
 *
 * [progress] is 0..1 (fraction of aired episodes seen); null hides the strip.
 * [rating] is the user's stored 1..10 rating, shown out of five; null hides the badge.
 */
@Composable
fun PosterCard(
    title: String,
    posterUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    rating: Int? = null,
    progress: Float? = null,
    selected: Boolean = false,
    showTitle: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "posterPress")

    Column(
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .then(
                    if (selected) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)
                    } else {
                        Modifier
                    },
                )
                .clip(MaterialTheme.shapes.medium)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        ) {
            Box(Modifier.fillMaxSize()) {
                PosterImage(url = posterUrl, title = title, modifier = Modifier.fillMaxSize())
                if (rating != null) {
                    RatingBadge(
                        rating = rating,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    )
                }
                if (progress != null) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color.Black.copy(alpha = 0.4f)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                .height(4.dp)
                                .background(MaterialTheme.colorScheme.tertiary),
                        )
                    }
                }
            }
        }
        if (showTitle) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
