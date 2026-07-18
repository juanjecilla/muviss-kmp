package com.codingpit.muviss.core.designsystem.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.core.designsystem.theme.tabular

/**
 * "★ 9" pill overlaid on posters — amber star + tabular number on a black
 * scrim so it stays legible over any artwork in either theme.
 */
@Composable
fun RatingBadge(
    rating: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.55f),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
        ) {
            Icon(
                MuvissIcons.Star,
                contentDescription = null,
                tint = Color(0xFFFFCB6B),
                modifier = Modifier.size(10.dp),
            )
            Text(
                text = " $rating",
                style = MaterialTheme.typography.labelSmall.tabular(),
                color = Color(0xFFFFCB6B),
            )
        }
    }
}
