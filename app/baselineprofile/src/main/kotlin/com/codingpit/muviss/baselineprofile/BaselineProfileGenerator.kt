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
 * cold start, the first-run introduction, then every bottom-bar tab, flinging the ones that hold lists.
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
        // Granted up front so turning alerts on (EPIC 47) never puts a system dialog over a tab below.
        device.executeShellCommand("pm grant $PACKAGE android.permission.POST_NOTIFICATIONS")
        pressHome()
        startActivityAndWait()
        walkThroughIntroduction()

        for (tab in TABS) {
            device.wait(Until.findObject(By.text(tab)), TIMEOUT_MS)?.click()
            device.waitForIdle()
            fling(Direction.DOWN)
            fling(Direction.UP)
        }
    }

    // A fresh install opens on the introduction (EPIC 47), the first screens a new user really sees,
    // so they are part of the profile. An install that has seen it goes straight to Search, and the
    // first lookup simply finds nothing.
    private fun MacrobenchmarkScope.walkThroughIntroduction() {
        repeat(INTRODUCTION_PAGES) {
            val next = device.wait(Until.findObject(By.text("Next")), INTRODUCTION_WAIT_MS) ?: return@repeat
            next.click()
            device.waitForIdle()
        }
        device.findObject(By.text("Start"))?.click()
        device.waitForIdle()
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

        // Next taps from the first page to DONE on Android: Welcome, Library, Watch next, Discover, Import, Device
        // Backup (EPIC 51 WS6), Alerts, Muted genres, then Done. Without the Own TMDB Key page (byokEnabled is off).
        const val INTRODUCTION_PAGES = 8
        const val INTRODUCTION_WAIT_MS = 1_500L
        val TABS = listOf("Library", "Progress", "Search", "Profile", "Settings")
    }
}
