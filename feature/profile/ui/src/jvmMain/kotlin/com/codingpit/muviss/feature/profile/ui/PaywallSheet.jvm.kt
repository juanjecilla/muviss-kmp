package com.codingpit.muviss.feature.profile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** Desktop has no app store to buy through; the fallback sheet is the permanent answer here, not a placeholder. */
@Composable
actual fun rememberPaywallPresenter(): PaywallPresenter = remember { HandRolledPaywall() }
