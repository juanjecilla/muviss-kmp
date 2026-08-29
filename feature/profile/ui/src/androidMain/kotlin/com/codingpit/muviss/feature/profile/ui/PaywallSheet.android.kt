package com.codingpit.muviss.feature.profile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Where `purchases-kmp-ui`'s server-driven paywall goes once a Play Console
 * app and a RevenueCat product exist (ADR 0012, Phase D). Until then this
 * target uses the same fallback as every other one.
 */
@Composable
actual fun rememberPaywallPresenter(): PaywallPresenter = remember { HandRolledPaywall() }
