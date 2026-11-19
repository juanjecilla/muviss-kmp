package com.codingpit.muviss.core.designsystem.layout

import androidx.compose.runtime.Composable

/**
 * Draws the status bar's icons light for as long as this is in the composition.
 *
 * For a screen whose artwork runs under the status bar (Detail, Episode Detail): the icons
 * otherwise follow the *system* theme, so a light-theme phone paints dark icons onto a dark
 * scrim. The previous appearance is restored on leaving. Only Android has anything to set;
 * every other target's actual is a no-op.
 */
@Composable
expect fun LightStatusBarIcons()
