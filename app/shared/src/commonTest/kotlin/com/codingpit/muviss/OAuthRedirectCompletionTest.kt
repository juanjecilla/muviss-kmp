@file:OptIn(ExperimentalTestApi::class)

package com.codingpit.muviss

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the shape of `MuvissApp`'s OAuth completion, which is easy to get
 * silently wrong and was.
 *
 * The redirect arrives as Activity state, the effect is keyed on it, and the
 * code has to be consumed so a re-delivered intent cannot redeem it twice.
 * Consuming clears the key, and a `LaunchedEffect` is cancelled the instant
 * its key changes — so an exchange awaited *inside* the effect never
 * finishes. Nothing about that failure is visible: no crash, no error, the
 * user simply lands back in the app still signed out.
 *
 * These reproduce the structure rather than call `MuvissApp` directly, which
 * would need the whole Koin graph, a database and a theme. What is under test
 * is the effect/scope arrangement, and that is reproduced exactly.
 */
class OAuthRedirectCompletionTest {

    /** The fixed version: the exchange runs in the composition's scope, so clearing the key cannot cancel it. */
    @Composable
    private fun Subject(authCode: String?, onConsumed: () -> Unit, exchange: suspend (String) -> Unit) {
        val scope = rememberCoroutineScope()
        LaunchedEffect(authCode) {
            val code = authCode ?: return@LaunchedEffect
            onConsumed()
            scope.launch { exchange(code) }
        }
    }

    /** The original: awaited inside the effect, so consuming the code cancels it. Kept to prove the test can tell them apart. */
    @Composable
    private fun RegressedSubject(authCode: String?, onConsumed: () -> Unit, exchange: suspend (String) -> Unit) {
        LaunchedEffect(authCode) {
            val code = authCode ?: return@LaunchedEffect
            onConsumed()
            exchange(code)
        }
    }

    @Test
    fun the_exchange_completes_even_though_consuming_the_code_clears_the_effects_key() = runComposeUiTest {
        val started = CompletableDeferred<String>()
        val release = CompletableDeferred<Unit>()
        var finishedWith: String? = null

        setContent {
            var code by remember { mutableStateOf<String?>("auth-code") }
            Subject(
                authCode = code,
                onConsumed = { code = null },
                exchange = { value ->
                    started.complete(value)
                    // Suspends across the recomposition that clears the key —
                    // the exact window the old code died in.
                    release.await()
                    finishedWith = value
                },
            )
        }
        waitForIdle()

        assertTrue(started.isCompleted, "the exchange should have been reached at all")
        assertEquals("auth-code", started.getCompleted())

        release.complete(Unit)
        waitForIdle()

        assertEquals("auth-code", finishedWith, "the exchange must survive the code being consumed")
    }

    @Test
    fun the_original_arrangement_is_cancelled_and_this_test_can_see_it() = runComposeUiTest {
        val release = CompletableDeferred<Unit>()
        var finishedWith: String? = null

        setContent {
            var code by remember { mutableStateOf<String?>("auth-code") }
            RegressedSubject(
                authCode = code,
                onConsumed = { code = null },
                exchange = { value ->
                    release.await()
                    finishedWith = value
                },
            )
        }
        waitForIdle()
        release.complete(Unit)
        waitForIdle()

        // Guards the guard: if this ever starts passing, the assertion above
        // has stopped proving anything.
        assertEquals(null, finishedWith, "awaiting inside the keyed effect is cancelled when the key clears")
    }
}
