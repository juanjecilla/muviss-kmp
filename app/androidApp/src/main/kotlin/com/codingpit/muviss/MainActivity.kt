package com.codingpit.muviss

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat

/**
 * Thin Android host. Two EPIC 5 (new-episode notifications) concerns live
 * here rather than in shared `commonMain` code, because both are
 * Android-specific platform APIs with no KMP seam elsewhere in the app yet:
 *
 * - The `POST_NOTIFICATIONS` runtime permission (API 33+), requested once on
 *   first app open — the simplest single decision point that covers every
 *   user, rather than tying it to a specific Settings action.
 * - Reading the notification tap's deep-link extra (`EXTRA_DEEP_LINK_MEDIA_ID`,
 *   set by `notifications.NewEpisodesNotifier`) and forwarding it into
 *   `MuvissApp()`, which navigates to that show's `DetailRoute` once
 *   composed (see its doc comment). `singleTask` launch mode (manifest) plus
 *   [onNewIntent] mean re-tapping a notification while the app is already
 *   running updates the same instance instead of stacking a new one.
 */
class MainActivity : ComponentActivity() {

    private var deepLinkMediaId by mutableStateOf<String?>(null)

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* denied => graceful no-op, see NewEpisodesNotifier */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        deepLinkMediaId = intent.deepLinkMediaIdExtra()
        requestNotificationPermissionIfNeeded()

        setContent {
            MuvissApp(
                deepLinkMediaId = deepLinkMediaId,
                onDeepLinkConsumed = { deepLinkMediaId = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkMediaId = intent.deepLinkMediaIdExtra()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun Intent.deepLinkMediaIdExtra(): String? = getStringExtra(EXTRA_DEEP_LINK_MEDIA_ID)

    companion object {
        /** Set by `notifications.NewEpisodesNotifier` on a per-show notification's tap intent. */
        const val EXTRA_DEEP_LINK_MEDIA_ID = "com.codingpit.muviss.DEEP_LINK_MEDIA_ID"
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    MuvissApp()
}
