package com.codingpit.muviss.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The critical path a new user takes, recorded as Muviss's baseline profile:
 * cold start, then every bottom-bar tab, flinging the ones that hold lists.
 *
 * Runs against whatever data the device has — a fresh install shows empty
 * states, which is the first-run path and still composes each screen. Tabs are
 * found by their English labels, so run it with the app in English
 * (`adb shell cmd locale set-app-locales com.codingpit.muviss --locales en-US`).
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        // The permission dialog fires on first launch (#73) and would sit
        // on top of every tab below.
        device.executeShellCommand("pm grant $PACKAGE android.permission.POST_NOTIFICATIONS")
        pressHome()
        startActivityAndWait()

        for (tab in TABS) {
            device.wait(Until.findObject(By.text(tab)), TIMEOUT_MS)?.click()
            device.waitForIdle()
            fling(Direction.DOWN)
            fling(Direction.UP)
        }
    }

    // Re-found on every fling: a list recomposes as it loads, and a node held
    // across that goes stale. A tab with nothing scrollable is skipped.
    private fun MacrobenchmarkScope.fling(direction: Direction) {
        runCatching {
            device.findObject(By.scrollable(true))?.run {
                setGestureMargin(device.displayWidth / GESTURE_MARGIN_FRACTION)
                fling(direction)
            }
        }
        device.waitForIdle()
    }

    private companion object {
        const val PACKAGE = "com.codingpit.muviss"
        const val TIMEOUT_MS = 5_000L
        const val GESTURE_MARGIN_FRACTION = 5
        val TABS = listOf("Library", "Progress", "Search", "Profile", "Settings")
    }
}
