package com.codingpit.muviss.ios

import com.codingpit.muviss.core.common.review.ReviewPrompter
import com.codingpit.muviss.core.designsystem.share.foregroundWindowScene
import platform.StoreKit.SKStoreReviewController

/**
 * iOS's [ReviewPrompter] (#410): StoreKit's review sheet, asked for in the foreground-active scene.
 *
 * The scene-based call is the one that targets the window the person is using on a multi-scene
 * iPad; the older unscoped call picks a scene itself and can pick a background one. Apple rations
 * the sheet and does not say whether it was drawn, so `true` means it was requested.
 */
internal object IosReviewPrompter : ReviewPrompter {
    override suspend fun prompt(): Boolean {
        val scene = foregroundWindowScene() ?: return false
        SKStoreReviewController.requestReviewInScene(scene)
        return true
    }
}
