package com.codingpit.muviss.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Shape scale: extraSmall = rating badges/small chips, small = text fields/
 * snackbar/menus, medium = poster cards/stat tiles/list rows, large =
 * carousel cards/dialogs, extraLarge = bottom sheets. Buttons/chips/avatars
 * use full (CircleShape) at the call site.
 */
val MuvissShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
