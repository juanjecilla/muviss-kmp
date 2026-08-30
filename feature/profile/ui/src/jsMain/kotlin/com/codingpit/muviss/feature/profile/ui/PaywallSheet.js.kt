package com.codingpit.muviss.feature.profile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** No store on the web either — same permanent fallback as desktop. */
@Composable
actual fun rememberPaywallPresenter(): PaywallPresenter = remember { HandRolledPaywall() }
