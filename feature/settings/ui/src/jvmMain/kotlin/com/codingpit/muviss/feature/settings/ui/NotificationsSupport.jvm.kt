package com.codingpit.muviss.feature.settings.ui

import com.codingpit.muviss.feature.settings.ui.generated.resources.Res
import com.codingpit.muviss.feature.settings.ui.generated.resources.notifications_note_desktop
import org.jetbrains.compose.resources.StringResource

/**
 * Desktop checks on a timer inside the running app (`DesktopEpisodeRefresh`)
 * because there is no WorkManager or `BGTaskScheduler` to hand the job to. The
 * caveat is in the copy rather than left for the user to discover.
 */
actual val notificationsSupportNote: StringResource = Res.string.notifications_note_desktop
