package com.codingpit.muviss.core.common.notifications

/**
 * Whether this platform ever shows a new-episode notification (EPIC 47). Android, iOS
 * and desktop do (desktop only while a window is open); the web build has no notifier
 * at all, so Onboarding's alerts page and the Notification Nudge are not offered there.
 */
expect val platformCanNotify: Boolean
