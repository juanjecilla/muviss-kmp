package com.codingpit.muviss.notifications

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.codingpit.muviss.core.common.notifications.SystemNotificationSettings

/** Android's answer to [SystemNotificationSettings] (EPIC 30, #73). */
class AndroidNotificationSettings(private val context: Context) : SystemNotificationSettings {

    override fun blocked(): Boolean = !NotificationPermission.granted(context)

    override fun open() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

/** Whether Muviss may post notifications right now: the app switch in system settings, and on 13+ the runtime permission. */
internal object NotificationPermission {
    fun granted(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
}
