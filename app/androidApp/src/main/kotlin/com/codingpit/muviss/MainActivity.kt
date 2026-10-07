package com.codingpit.muviss

import android.Manifest
import android.app.AlertDialog
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
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.codingpit.muviss.core.sync.OAUTH_CODE_PARAM
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.notifications.NotificationPermission
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * Thin Android host. Two EPIC 5 (new-episode notifications) concerns live
 * here rather than in shared `commonMain` code, because both are
 * Android-specific platform APIs with no KMP seam elsewhere in the app yet:
 *
 * - The `POST_NOTIFICATIONS` runtime permission (API 33+), asked for once the
 *   library holds a TV show — the first moment a new-episode alert means
 *   anything — after a one-line rationale (EPIC 30, #73). It used to fire on
 *   the very first launch, before the user had a reason to accept, and a
 *   denial on 13+ is sticky.
 * - Reading the notification tap's deep-link extra (`EXTRA_DEEP_LINK_MEDIA_ID`,
 *   set by `notifications.NewEpisodesNotifier`) and forwarding it into
 *   `MuvissApp()`, which navigates to that show's `DetailRoute` once
 *   composed (see its doc comment). `singleTask` launch mode (manifest) plus
 *   [onNewIntent] mean re-tapping a notification while the app is already
 *   running updates the same instance instead of stacking a new one.
 */
class MainActivity : ComponentActivity() {

    private var deepLinkMediaId by mutableStateOf<String?>(null)
    private var oauthCode by mutableStateOf<String?>(null)

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* denied => graceful no-op, see NewEpisodesNotifier */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        deepLinkMediaId = intent.deepLinkMediaIdExtra()
        oauthCode = intent.oauthCode()
        askForNotificationsOnceAShowIsSaved()

        setContent {
            MuvissApp(
                deepLinkMediaId = deepLinkMediaId,
                onDeepLinkConsumed = { deepLinkMediaId = null },
                oauthCode = oauthCode,
                onOAuthCodeConsumed = { oauthCode = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkMediaId = intent.deepLinkMediaIdExtra()
        oauthCode = intent.oauthCode()
    }

    /**
     * Waits for the first saved TV show, then explains and asks — once. The
     * "asked" mark is a per-device UI fact, so it lives in plain preferences
     * rather than the synced database.
     */
    private fun askForNotificationsOnceAShowIsSaved() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED, false) || NotificationPermission.granted(this)) return
        val collection = GlobalContext.get().get<CollectionApi>()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                collection.observeSummaries().first { all -> all.any { it.mediaId.type == MediaType.TV } }
                if (prefs.getBoolean(KEY_ASKED, false) || NotificationPermission.granted(this@MainActivity)) return@repeatOnLifecycle
                prefs.edit { putBoolean(KEY_ASKED, true) }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.permission_title)
                    .setMessage(R.string.permission_body)
                    .setPositiveButton(R.string.permission_allow) { _, _ -> requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
                    .setNegativeButton(R.string.permission_not_now, null)
                    .show()
            }
        }
    }

    private fun Intent.deepLinkMediaIdExtra(): String? = getStringExtra(EXTRA_DEEP_LINK_MEDIA_ID)

    /**
     * The authorization code from an OAuth redirect (`muviss://auth-callback?code=…`,
     * see the manifest's second intent filter and ADR 0014).
     *
     * Null for every other launch, including a cancelled sign-in — GoTrue
     * redirects back with `error`/`error_description` instead of `code` when
     * the user declines, and there is nothing to complete in that case.
     */
    private fun Intent.oauthCode(): String? = data?.takeIf { it.scheme == OAUTH_SCHEME && it.host == OAUTH_HOST }?.getQueryParameter(OAUTH_CODE_PARAM)

    companion object {
        /** Set by `notifications.NewEpisodesNotifier` on a per-show notification's tap intent. */
        const val EXTRA_DEEP_LINK_MEDIA_ID = "com.codingpit.muviss.DEEP_LINK_MEDIA_ID"

        // Must match the manifest's intent filter and :core:sync's
        // OAUTH_REDIRECT_URI. Split into scheme/host here because that is how
        // an Android Uri exposes them.
        private const val OAUTH_SCHEME = "muviss"
        private const val OAUTH_HOST = "auth-callback"

        private const val PREFS = "muviss_host"
        private const val KEY_ASKED = "notification_permission_asked"
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    MuvissApp()
}
