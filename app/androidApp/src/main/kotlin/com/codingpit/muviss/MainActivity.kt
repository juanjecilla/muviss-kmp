package com.codingpit.muviss

import android.Manifest
import android.content.Intent
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
import com.codingpit.muviss.core.common.notifications.NotificationPermissionRequester
import com.codingpit.muviss.core.sync.OAUTH_CODE_PARAM
import com.codingpit.muviss.notifications.AndroidNotificationPermissionRequester
import org.koin.core.context.GlobalContext

/**
 * Thin Android host. Two concerns live here rather than in shared `commonMain` code,
 * because both are Android-specific platform APIs with no KMP seam elsewhere in the app yet:
 *
 * - The `POST_NOTIFICATIONS` runtime permission (API 33+). It is only ever asked for at the
 *   moment the user turns notifications on — in Onboarding, the Notification Nudge or
 *   Settings (EPIC 47) — through [AndroidNotificationPermissionRequester], which this
 *   activity lends its result launcher to. It used to be asked once a show was saved
 *   (EPIC 30, #73), and before that on the very first launch; a denial on 13+ is sticky.
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
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationPermissions?.onResult(granted)
        }

    /** The process-wide requester, if the host bound the Android one (it always does outside tests). */
    private val notificationPermissions: AndroidNotificationPermissionRequester?
        get() = GlobalContext.getOrNull()?.get<NotificationPermissionRequester>() as? AndroidNotificationPermissionRequester

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        deepLinkMediaId = intent.deepLinkMediaIdExtra()
        oauthCode = intent.oauthCode()
        notificationPermissions?.attach { requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }

        setContent {
            MuvissApp(
                deepLinkMediaId = deepLinkMediaId,
                onDeepLinkConsumed = { deepLinkMediaId = null },
                oauthCode = oauthCode,
                onOAuthCodeConsumed = { oauthCode = null },
            )
        }
    }

    override fun onDestroy() {
        // A launcher belongs to its activity; a request in flight when it goes is a refusal.
        notificationPermissions?.detach()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkMediaId = intent.deepLinkMediaIdExtra()
        oauthCode = intent.oauthCode()
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
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    MuvissApp()
}
