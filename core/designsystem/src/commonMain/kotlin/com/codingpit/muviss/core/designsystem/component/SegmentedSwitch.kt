package com.codingpit.muviss.core.designsystem.component

import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The 2–3 segment switch used for Library/Lists, Watch Next/Upcoming, etc.
 * Wraps M3 [SingleChoiceSegmentedButtonRow] so every screen gets identical
 * shape and colors (amber selected via the theme's secondaryContainer roles).
 */
@Composable
fun SegmentedSwitch(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                icon = {},
            ) {
                Text(option)
            }
        }
    }
}
