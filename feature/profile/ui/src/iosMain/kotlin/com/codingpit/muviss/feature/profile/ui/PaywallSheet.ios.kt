package com.codingpit.muviss.feature.profile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** Paired with the Android actual: `purchases-kmp-ui` covers both, and neither is wired yet (ADR 0018, Phase D). */
@Composable
actual fun rememberPaywallPresenter(): PaywallPresenter = remember { HandRolledPaywall() }
