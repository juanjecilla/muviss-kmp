package com.codingpit.muviss.feature.search.ui

import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The first page of a viewer whose tapped image is being replaced, as a table of states. Each row is one state the two
 * loads can be in; each says what the page draws and the size of that picture (Telephoto's bounds come from it). The
 * sizes are only known once a load has succeeded, so a load that has not succeeded has no size here.
 *
 * The UI test (`ImageViewerFallbackTest`) drives the same states through the real loader.
 */
class GalleryPageStateTest {

    private val tappedSize = Size(800f, 450f)
    private val replacementSize = Size(1600f, 900f)

    private data class Row(
        val state: String,
        val replacesSeed: Boolean,
        val replacementLoaded: Boolean,
        val tappedLoaded: Boolean,
        val shown: ShownImage,
        val size: Size?,
    )

    private val table = listOf(
        Row("replacement pending, tapped loaded", true, false, true, ShownImage.Tapped, tappedSize),
        Row("replacement pending, tapped not loaded yet", true, false, false, ShownImage.Nothing, null),
        Row("replacement succeeded", true, true, true, ShownImage.Replacement, replacementSize),
        Row("replacement succeeded before the tapped image loaded", true, true, false, ShownImage.Replacement, replacementSize),
        Row("replacement failed before the tapped image loaded", true, false, false, ShownImage.Nothing, null),
        Row("replacement failed, then the tapped image loaded", true, false, true, ShownImage.Tapped, tappedSize),
        Row("replacement failed after the tapped image loaded", true, false, true, ShownImage.Tapped, tappedSize),
        Row("tapped image failed, replacement pending", true, false, false, ShownImage.Nothing, null),
        Row("tapped image failed, replacement succeeded", true, true, false, ShownImage.Replacement, replacementSize),
        Row("tapped image failed and replacement failed", true, false, false, ShownImage.Nothing, null),
        Row("page comes back mid-load, tapped image cached", true, false, true, ShownImage.Tapped, tappedSize),
        Row("rotation remounts with the replacement cached", true, true, true, ShownImage.Replacement, replacementSize),
        Row("page that replaces nothing, own picture loaded", false, true, false, ShownImage.Replacement, replacementSize),
        Row("page that replaces nothing, own picture pending", false, false, false, ShownImage.Nothing, null),
    )

    @Test
    fun every_state_draws_the_picture_and_reports_the_size_the_table_gives() {
        for (row in table) {
            val shown = shownImage(row.replacesSeed, row.replacementLoaded, row.tappedLoaded)
            assertEquals(row.shown, shown, "what is drawn, in state: ${row.state}")

            val size = shownSize(
                shown,
                replacementSize = replacementSize.takeIf { row.replacementLoaded },
                tappedSize = tappedSize.takeIf { row.tappedLoaded },
            )
            assertEquals(row.size, size, "the reported size, in state: ${row.state}")
        }
    }

    @Test
    fun the_tapped_image_is_never_drawn_for_a_page_that_replaces_nothing() {
        assertEquals(ShownImage.Nothing, shownImage(replacesSeed = false, replacementLoaded = false, tappedLoaded = true))
    }
}
