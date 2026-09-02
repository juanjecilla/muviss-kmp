package com.codingpit.muviss.feature.settings.ui

/**
 * Desktop checks on a timer inside the running app (`DesktopEpisodeRefresh`)
 * because there is no WorkManager or `BGTaskScheduler` to hand the job to. The
 * caveat is in the copy rather than left for the user to discover.
 */
actual val notificationsSupportNote: String = "Reminders for new episodes, while Muviss is open"
