package com.codingpit.muviss.feature.settings.ui

/**
 * What the Settings notifications row can honestly promise on this platform.
 *
 * The row used to read "coming soon" everywhere, which was wrong in both
 * directions at once: Android has posted new-episode notifications since EPIC 5
 * and iOS since EPIC 11, while desktop and web rendered a live switch that
 * persisted a preference nothing on those platforms ever read.
 *
 * The difference is not cosmetic, so the copy carries it. Android and iOS
 * schedule real background work (WorkManager, `BGTaskScheduler`) and notify
 * with the app shut; desktop can only check while a window is open; the web
 * build has no notifier at all.
 */
expect val notificationsSupportNote: String
