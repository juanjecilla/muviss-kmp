package com.codingpit.muviss.core.designsystem.component

import kotlin.test.Test
import kotlin.test.assertEquals

class PosterSizeTest {

    private val stored = "https://image.tmdb.org/t/p/w500/abc123.jpg"

    @Test
    fun thumbnail_requests_w185() {
        assertEquals("https://image.tmdb.org/t/p/w185/abc123.jpg", sizedImageUrl(stored, PosterSize.Thumbnail))
    }

    @Test
    fun grid_requests_w342() {
        assertEquals("https://image.tmdb.org/t/p/w342/abc123.jpg", sizedImageUrl(stored, PosterSize.Grid))
    }

    @Test
    fun detail_requests_w500_and_leaves_a_stored_w500_url_untouched() {
        assertEquals(stored, sizedImageUrl(stored, PosterSize.Detail))
    }

    @Test
    fun any_stored_width_is_rewritten_not_only_w500() {
        assertEquals(
            "https://image.tmdb.org/t/p/w185/still.jpg",
            sizedImageUrl("https://image.tmdb.org/t/p/w780/still.jpg", PosterSize.Thumbnail),
        )
    }

    @Test
    fun a_url_that_is_not_a_tmdb_image_is_returned_unchanged() {
        val other = "https://example.com/t/p/w500/abc.jpg"
        assertEquals(other, sizedImageUrl(other, PosterSize.Thumbnail))
    }

    @Test
    fun an_original_size_url_has_no_width_segment_and_is_unchanged() {
        val original = "https://image.tmdb.org/t/p/original/abc.jpg"
        assertEquals(original, sizedImageUrl(original, PosterSize.Grid))
    }

    @Test
    fun only_the_width_segment_is_rewritten_when_the_path_contains_another_w_segment() {
        assertEquals(
            "https://image.tmdb.org/t/p/w185/w500/abc.jpg",
            sizedImageUrl("https://image.tmdb.org/t/p/w500/w500/abc.jpg", PosterSize.Thumbnail),
        )
    }

    @Test
    fun the_three_size_classes_ask_for_ascending_widths() {
        assertEquals(listOf(185, 342, 500), PosterSize.entries.map { it.tmdbWidth })
    }
}
