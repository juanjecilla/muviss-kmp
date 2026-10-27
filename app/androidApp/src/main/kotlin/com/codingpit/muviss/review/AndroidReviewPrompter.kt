package com.codingpit.muviss.review

import android.app.Activity
import android.content.Context
import com.codingpit.muviss.core.common.crash.reportFailure
import com.codingpit.muviss.core.common.review.ReviewPrompter
import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.requestReview
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.ReviewManagerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android's [ReviewPrompter] (#410): Play's In-App Review sheet.
 *
 * Launching the flow needs the foreground Activity, which only [MainActivity] has, so it lends
 * itself here with [attach] and takes itself back in [detach], the same arrangement as
 * `AndroidNotificationPermissionRequester`. With none attached (a worker-started process, or the
 * activity already gone) the answer is "not asked", and nothing is recorded.
 *
 * Play rations the sheet and returns no signal for whether it was drawn, so a `true` means the
 * request reached the store, not that a person saw it. A failure to request (no Play services,
 * a sideload) is reported rather than dropped: it is unexpected on a store build.
 */
class AndroidReviewPrompter(context: Context) : ReviewPrompter {

    private val manager: ReviewManager = ReviewManagerFactory.create(context)
    private var activity: Activity? = null

    fun attach(activity: Activity) {
        this.activity = activity
    }

    fun detach(activity: Activity) {
        if (this.activity === activity) this.activity = null
    }

    override suspend fun prompt(): Boolean {
        val host = activity ?: return false
        return withContext(Dispatchers.Main.immediate) {
            val info = runCatching { manager.requestReview() }.reportFailure().getOrNull() ?: return@withContext false
            runCatching { manager.launchReview(host, info) }.reportFailure().isSuccess
        }
    }
}
