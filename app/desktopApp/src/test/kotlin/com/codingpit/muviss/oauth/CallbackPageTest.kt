package com.codingpit.muviss.oauth

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class CallbackPageTest {

    @Test
    fun english_by_default_for_any_other_language() {
        assertContains(callbackPage(signedIn = true, language = "en"), "You're signed in")
        assertContains(callbackPage(signedIn = true, language = "fr"), "You're signed in")
        assertContains(callbackPage(signedIn = false, language = "en"), "Sign-in didn't finish")
    }

    @Test
    fun spanish_under_a_spanish_locale() {
        val signedIn = callbackPage(signedIn = true, language = "es")
        assertContains(signedIn, "Has iniciado sesión")
        assertContains(signedIn, "Puedes cerrar esta pestaña")
        assertContains(signedIn, "lang=es")
        assertFalse(signedIn.contains("You can close"))

        val failed = callbackPage(signedIn = false, language = "es")
        assertContains(failed, "No se completó el inicio de sesión")
        assertFalse(failed.contains("Sign-in didn't finish"))
    }

    @Test
    fun the_page_declares_utf8_so_accents_survive() {
        assertContains(callbackPage(signedIn = true, language = "es"), "charset=utf-8")
    }
}
