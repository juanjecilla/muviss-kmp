package com.codingpit.muviss.feature.profile.ui

import com.codingpit.muviss.core.designsystem.text.UiText
import com.codingpit.muviss.core.designsystem.text.resolveAsync
import com.codingpit.muviss.feature.profile.domain.AutomaticSyncMode
import com.codingpit.muviss.feature.profile.domain.LastSynced
import com.codingpit.muviss.feature.profile.domain.SignInFailure
import com.codingpit.muviss.feature.profile.domain.SyncFailureKind
import com.codingpit.muviss.feature.profile.domain.SyncStatusDetail
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The sync wording that used to live in `:feature:profile:domain` (#217). The
 * JVM default locale picks `values-es`, so each test pins it explicitly.
 */
class SyncCopyTest {

    private fun <T> inLocale(tag: String, block: suspend () -> T): T {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag(tag))
        return try {
            runBlocking { block() }
        } finally {
            Locale.setDefault(previous)
        }
    }

    private suspend fun UiText.read(): String = resolveAsync()

    // --- English ---------------------------------------------------------------

    @Test
    fun unsent_changes_are_counted_and_one_is_singular() = inLocale("en-US") {
        assertEquals("4 changes waiting", SyncStatusDetail.Waiting(4).toUiText().read())
        assertEquals("1 change waiting", SyncStatusDetail.Waiting(1).toUiText().read())
    }

    @Test
    fun a_failure_says_why_in_words() = inLocale("en-US") {
        assertEquals("Last sync failed: you seem to be offline", SyncStatusDetail.Failed(SyncFailureKind.Offline).toUiText().read())
    }

    @Test
    fun an_account_mismatch_is_explained() = inLocale("en-US") {
        assertTrue(SyncStatusDetail.AccountChanged.toUiText().read().contains("different account"))
    }

    @Test
    fun every_failure_kind_has_its_own_copy_and_none_leaks_technical_text() = inLocale("en-US") {
        val copies = SyncFailureKind.entries.map { it.toUiText().read() }

        assertEquals(copies.size, copies.toSet().size, "each reason reads differently")
        copies.forEach { copy ->
            listOf("HTTP", "Exception", "Supabase", "401", "500").forEach { assertTrue(!copy.contains(it), "\"$copy\" mentions $it") }
        }
    }

    @Test
    fun each_platform_gets_a_description_that_does_not_overpromise() = inLocale("en-US") {
        val inBackground = AutomaticSyncMode.InBackground.toUiText().read()
        val system = AutomaticSyncMode.WhenSystemAllows.toUiText().read()
        val open = AutomaticSyncMode.WhileOpen.toUiText().read()

        assertTrue(inBackground.contains("background"))
        assertTrue(system.contains("system allows"))
        assertTrue(open.contains("while Muviss is open", ignoreCase = true))
        assertEquals(3, setOf(inBackground, system, open).size)
    }

    @Test
    fun the_last_sync_reads_as_the_old_label_did() = inLocale("en-US") {
        assertEquals("Never synced", LastSynced.Never.toUiText().read())
        assertEquals("Synced just now", LastSynced.JustNow.toUiText().read())
        assertEquals("Synced 5m ago", LastSynced.MinutesAgo(5).toUiText().read())
        assertEquals("Synced 3h ago", LastSynced.HoursAgo(3).toUiText().read())
        assertEquals("Synced 2d ago", LastSynced.DaysAgo(2).toUiText().read())
    }

    @Test
    fun a_sign_in_failure_has_words_and_the_providers_own_reason_passes_through() = inLocale("en-US") {
        assertEquals("Couldn't finish signing in", SignInFailure.Generic.toUiText().read())
        assertEquals("Couldn't finish signing in", SignInFailure.Declined(" ").toUiText().read())
        assertEquals("User declined", SignInFailure.Declined("User declined").toUiText().read())
        assertEquals("Sign-in timed out after 5 minutes. Try again.", SignInFailure.TimedOut(5).toUiText().read())
        assertEquals("Sign-in timed out after 1 minute. Try again.", SignInFailure.TimedOut(1).toUiText().read())
        assertEquals("The sign-in redirect carried no authorization code.", SignInFailure.NoCode.toUiText().read())
    }

    // --- Spanish ---------------------------------------------------------------

    @Test
    fun spanish_users_read_every_sync_line_in_spanish() = inLocale("es-ES") {
        assertEquals("4 cambios pendientes", SyncStatusDetail.Waiting(4).toUiText().read())
        assertEquals("1 cambio pendiente", SyncStatusDetail.Waiting(1).toUiText().read())
        assertEquals("La sesión ha caducado, vuelve a iniciar sesión.", SyncSessionExpiredText.read())
        assertEquals("La última sincronización falló: parece que no tienes conexión", SyncStatusDetail.Failed(SyncFailureKind.Offline).toUiText().read())
        assertEquals("Sincronizado hace 5 min", LastSynced.MinutesAgo(5).toUiText().read())
        assertEquals("Nunca sincronizado", LastSynced.Never.toUiText().read())
        assertEquals("El inicio de sesión caducó tras 5 minutos. Inténtalo de nuevo.", SignInFailure.TimedOut(5).toUiText().read())
        assertEquals("El inicio de sesión caducó tras 1 minuto. Inténtalo de nuevo.", SignInFailure.TimedOut(1).toUiText().read())
    }

    @Test
    fun no_spanish_line_is_left_in_english() = inLocale("es-ES") {
        val all = SyncFailureKind.entries.map { it.toUiText() } +
            AutomaticSyncMode.entries.map { it.toUiText() } +
            listOf(LastSynced.Never, LastSynced.JustNow, LastSynced.MinutesAgo(2), LastSynced.HoursAgo(2), LastSynced.DaysAgo(2)).map { it.toUiText() } +
            listOf(SyncStatusDetail.AccountChanged.toUiText(), SignInFailure.Generic.toUiText(), SignInFailure.NoCode.toUiText())
        val spanish = all.map { it.read() }
        val english = inLocale("en-US") { all.map { it.read() } }

        spanish.zip(english).forEach { (es, en) -> assertNotEquals(en, es, "\"$en\" was not translated") }
    }
}
