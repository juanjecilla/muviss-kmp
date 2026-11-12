package com.codingpit.muviss.notifications

import android.content.Context
import android.os.Build
import com.codingpit.muviss.core.common.notifications.NotificationPermissionRequester
import kotlinx.coroutines.CompletableDeferred

/**
 * Android's [NotificationPermissionRequester] (EPIC 47).
 *
 * A runtime permission can only be requested from an Activity's result launcher,
 * and the launcher only exists once `MainActivity` has registered it, so the
 * activity lends it here with [attach] and takes it back in [detach]. With none
 * attached (a process that cold-started for a worker) a request is a refusal:
 * nothing may show a system prompt over no window.
 *
 * Below API 33 there is no runtime permission, so the answer is just whether the
 * app-level switch in system settings is on.
 *
 * It also keeps the one fact Android does not report: whether the prompt was ever shown on this device
 * (#326). Android says the same thing about "never asked" and "refused", so [canAskAgain] reads that fact
 * with the activity's rationale: once asked, a refusal with no rationale left is a refusal for good. The fact
 * is local (SharedPreferences), never synced. A device that refused before this build has no fact, so it is
 * treated as never asked: the one extra tap it costs is the price of not guessing.
 */
class AndroidNotificationPermissionRequester(private val context: Context) : NotificationPermissionRequester {

    private var launch: (() -> Unit)? = null
    private var rationale: (() -> Boolean)? = null
    private var pending: CompletableDeferred<Boolean>? = null

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    fun attach(launch: () -> Unit, rationale: () -> Boolean = { true }) {
        this.launch = launch
        this.rationale = rationale
    }

    fun detach() {
        launch = null
        rationale = null
        pending?.complete(false)
        pending = null
    }

    /**
     * False only when the prompt has been shown here before, the OS will no longer show it (no rationale), and the
     * permission is not granted. With no activity lent, nothing can be asked, so this cannot say no.
     */
    fun canAskAgain(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        if (NotificationPermission.granted(context)) return true
        if (!prefs.getBoolean(KEY_ASKED_ONCE, false)) return true
        val lentRationale = rationale ?: return true
        return lentRationale()
    }

    /** The system's answer, from the activity's launcher. */
    fun onResult(granted: Boolean) {
        pending?.complete(granted)
        pending = null
    }

    override suspend fun request(): Boolean {
        if (NotificationPermission.granted(context)) return true
        // Nothing to ask for: either the OS has no runtime permission here, or a refusal is already
        // sticky and the launcher below would answer "no" at once. Either way the system settings
        // screen (`SystemNotificationSettings.open`) is the only way forward.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        // A second tap while the prompt is up waits for the same answer: replacing `pending`
        // would leave the first caller suspended with nothing left to complete it.
        pending?.let { return it.await() }
        val start = launch ?: return false
        val answer = CompletableDeferred<Boolean>().also { pending = it }
        prefs.edit().putBoolean(KEY_ASKED_ONCE, true).apply()
        start()
        return answer.await()
    }

    private companion object {
        const val PREFS = "notification_permission"
        const val KEY_ASKED_ONCE = "asked_once"
    }
}
