package com.codingpit.muviss.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.codingpit.muviss.MainActivity
import com.codingpit.muviss.feature.collection.api.NewEpisodesResult

/**
 * Turns [NewEpisodesResult]s into local notifications: one per show, plus a
 * group summary once there are more than [SUMMARY_THRESHOLD] (EPIC 5's "sane
 * batching" — without a summary, a user with a big library who opens the app
 * after a while could get flooded with individual alerts). Every show
 * notification's tap deep-links to that show's Detail screen via
 * [MainActivity]; the summary's tap just opens the app.
 *
 * [canPostNotifications] makes [notify] a graceful no-op — never a crash —
 * when the user denied `POST_NOTIFICATIONS` (API 33+) or disabled
 * notifications for the app entirely.
 */
class NewEpisodesNotifier(private val context: Context) {

    fun notify(results: List<NewEpisodesResult>) {
        if (results.isEmpty() || !canPostNotifications()) return

        ensureChannel()
        val manager = NotificationManagerCompat.from(context)

        results.forEach { result ->
            manager.notify(result.notificationId(), buildShowNotification(result))
        }
        if (results.size > SUMMARY_THRESHOLD) {
            manager.notify(SUMMARY_NOTIFICATION_ID, buildSummaryNotification(results))
        }
    }

    private fun buildShowNotification(result: NewEpisodesResult): Notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_popup_reminder)
        .setContentTitle(result.title)
        .setContentText(result.contentText())
        .setAutoCancel(true)
        .setGroup(GROUP_KEY)
        .setContentIntent(detailPendingIntent(result))
        .build()

    private fun buildSummaryNotification(results: List<NewEpisodesResult>): Notification {
        val style = NotificationCompat.InboxStyle()
        results.forEach { style.addLine(it.summaryLine()) }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("${results.size} shows have new episodes")
            .setContentText("Tap to open Muviss")
            .setStyle(style)
            .setAutoCancel(true)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setContentIntent(openAppPendingIntent())
            .build()
    }

    private fun detailPendingIntent(result: NewEpisodesResult): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_DEEP_LINK_MEDIA_ID, result.mediaId.toString())
        }
        return PendingIntent.getActivity(
            context,
            result.notificationId(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            SUMMARY_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_ID, "New episodes", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Alerts when a saved show has a new episode out"
        }
        manager.createNotificationChannel(channel)
    }

    private fun canPostNotifications(): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun NewEpisodesResult.notificationId(): Int = mediaId.toString().hashCode()

    /** Content text for the per-show notification; its title already carries the show's name. */
    private fun NewEpisodesResult.contentText(): String = latestEpisodeLabel?.let { "$it is out" } ?: "Now available"

    /** One line of the summary notification's inbox style, which has no per-show title to lean on. */
    private fun NewEpisodesResult.summaryLine(): String = latestEpisodeLabel?.let { "$it of $title is out" } ?: "$title is out"

    private companion object {
        const val CHANNEL_ID = "new_episodes"
        const val GROUP_KEY = "com.codingpit.muviss.NEW_EPISODES"
        const val SUMMARY_NOTIFICATION_ID = 0
        const val SUMMARY_THRESHOLD = 3
    }
}
