package com.codingpit.muviss.feature.search.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.LayoutDirection
import com.codingpit.muviss.core.designsystem.component.PosterSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ViewerGeometryTest {

    @Test
    fun fit_location_scales_a_small_picture_up_to_fill_the_viewport() {
        val location = FitContentLocation(Size(640f, 360f))

        assertEquals(Size(1280f, 720f), location.size(Size(1280f, 720f)))
        assertEquals(Offset.Zero, location.location(Size(1280f, 720f), LayoutDirection.Ltr).topLeft)
    }

    @Test
    fun fit_location_centres_a_tall_picture_inside_the_viewport() {
        val drawn = FitContentLocation(Size(780f, 1170f)).location(Size(412f, 892f), LayoutDirection.Ltr)

        assertEquals(412f, drawn.width, 0.01f)
        assertEquals(618f, drawn.height, 0.01f)
        assertEquals(137f, drawn.top, 0.01f)
    }

    @Test
    fun fit_location_without_a_known_picture_size_is_telephotos_unspecified_one() {
        assertNull(fitLocation(null) as? FitContentLocation)
        assertNull(fitLocation(Size.Unspecified) as? FitContentLocation)
        assertNull(fitLocation(Size(0f, 0f)) as? FitContentLocation)
        assertNotNull(fitLocation(Size(640f, 360f)) as? FitContentLocation)
    }

    @Test
    fun wheel_up_zooms_in_by_a_tenth_per_unit_of_scroll() {
        assertEquals(1.1f, assertNotNull(wheelZoomFactor(-1f)), 0.0001f)
    }

    @Test
    fun wheel_down_zooms_out_and_never_reaches_zero_however_large_the_scroll() {
        val gentle = assertNotNull(wheelZoomFactor(1f))
        assertEquals(1f / 1.1f, gentle, 0.0001f)

        val fling = assertNotNull(wheelZoomFactor(1_000f))
        assertTrue(fling > 0f, "a zoom factor must stay positive, was $fling")
        assertEquals(1f / 1.5f, fling, 0.0001f)
    }

    @Test
    fun wheel_at_rest_asks_for_no_zoom() {
        assertNull(wheelZoomFactor(0f))
    }

    @Test
    fun drawn_url_is_the_one_the_poster_requests_at_its_size() {
        val stored = "https://image.tmdb.org/t/p/w500/poster.jpg"

        assertEquals("https://image.tmdb.org/t/p/w342/poster.jpg", drawnImageUrl(stored, PosterSize.Grid))
        assertEquals(stored, drawnImageUrl(stored, PosterSize.Detail))
    }

    @Test
    fun drawn_url_is_null_when_there_is_no_artwork() {
        assertNull(drawnImageUrl(null, PosterSize.Grid))
        assertNull(drawnImageUrl("  ", PosterSize.Detail))
    }
}
