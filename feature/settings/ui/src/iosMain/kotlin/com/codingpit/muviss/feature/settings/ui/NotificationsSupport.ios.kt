package com.codingpit.muviss.feature.settings.ui

import com.codingpit.muviss.feature.settings.ui.generated.resources.Res
import com.codingpit.muviss.feature.settings.ui.generated.resources.notifications_note_ios
import org.jetbrains.compose.resources.StringResource

/**
 * iOS runs its background work when the system allows, not on a schedule, so the
 * copy says so: the two switches a person can flip that stop it are Low Power
 * Mode and Background App Refresh for Muviss, and the row names both.
 */
actual val notificationsSupportNote: StringResource = Res.string.notifications_note_ios
