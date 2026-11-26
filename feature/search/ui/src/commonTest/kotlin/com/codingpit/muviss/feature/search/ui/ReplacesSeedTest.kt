package com.codingpit.muviss.feature.search.ui

import com.codingpit.muviss.models.TitleImage
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReplacesSeedTest {

    private val tapped = TitleImage("https://image.tmdb.org/t/p/w780/backdrop.jpg")

    @Test
    fun the_same_file_at_another_address_replaces_the_tapped_image() {
        assertTrue(TitleImage("https://image.tmdb.org/t/p/original/backdrop.jpg").replacesSeed(tapped))
    }

    @Test
    fun the_tapped_image_itself_replaces_nothing() {
        assertFalse(tapped.replacesSeed(tapped))
    }

    @Test
    fun a_different_file_replaces_nothing() {
        assertFalse(TitleImage("https://image.tmdb.org/t/p/w780/other.jpg").replacesSeed(tapped))
    }

    @Test
    fun without_a_tapped_image_nothing_is_replaced() {
        assertFalse(tapped.replacesSeed(null))
    }
}
