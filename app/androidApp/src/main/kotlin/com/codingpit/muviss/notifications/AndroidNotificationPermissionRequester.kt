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
 */
class AndroidNotificationPermissionRequester(private val context: Context) : NotificationPermissionRequester {

    private var launch: (() -> Unit)? = null
    private var pending: CompletableDeferred<Boolean>? = null

    fun attach(launch: () -> Unit) {
        this.launch = launch
    }

    fun detach() {
        launch = null
        pending?.complete(false)
        pending = null
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
        start()
        return answer.await()
    }
}
