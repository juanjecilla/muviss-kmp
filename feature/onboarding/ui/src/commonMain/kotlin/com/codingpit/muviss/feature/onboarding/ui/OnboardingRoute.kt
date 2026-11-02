package com.codingpit.muviss.feature.onboarding.ui

import kotlinx.serialization.Serializable

/**
 * The introduction. [replay] is true when Settings > About > "Show introduction again" opens it:
 * it then returns to Settings instead of starting the app, and shows current values.
 */
@Serializable
data class OnboardingRoute(val replay: Boolean = false)
