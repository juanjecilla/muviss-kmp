package com.codingpit.muviss.core.designsystem.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.codingpit.muviss.core.designsystem.generated.resources.Res
import com.codingpit.muviss.core.designsystem.generated.resources.carousel_see_all
import org.jetbrains.compose.resources.stringResource

/** Carousel section header: titleMedium + optional amber "See all ›". */
@Composable
fun CarouselHeader(
    title: String,
    modifier: Modifier = Modifier,
    onSeeAll: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (onSeeAll != null) {
            TextButton(onClick = onSeeAll) {
                Text(stringResource(Res.string.carousel_see_all), color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
