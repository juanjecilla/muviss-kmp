package com.codingpit.muviss.core.common.notifications

/**
 * Whether this platform has the Watch next home-screen widget (EPIC 22): Android and iOS
 * do, desktop and web have no home screen. Onboarding's Watch next page only mentions
 * the widget where there is one.
 */
expect val platformHasHomeWidgets: Boolean
