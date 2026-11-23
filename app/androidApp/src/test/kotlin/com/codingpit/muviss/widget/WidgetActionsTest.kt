package com.codingpit.muviss.widget

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** #888: the title a tick keeps for its Undo row, which a repeated tick must not drop. */
class WidgetActionsTest {

    private val episode = "1399/1/10"

    @Test
    fun `a title found in Watch Next is kept`() {
        assertEquals("Game of Thrones", rememberedTickTitle(episode, "Game of Thrones", null, null))
    }

    @Test
    fun `a repeated tick of the marked episode keeps the remembered title`() {
        assertEquals("Game of Thrones", rememberedTickTitle(episode, null, episode, "Game of Thrones"))
    }

    @Test
    fun `a tick of a different episode does not reuse the remembered title`() {
        assertNull(rememberedTickTitle("1437/1/1", null, episode, "Game of Thrones"))
    }

    @Test
    fun `no marker and no title leaves nothing to keep`() {
        assertNull(rememberedTickTitle(episode, null, null, null))
    }

    @Test
    fun `a title found now replaces the remembered one for the same episode`() {
        assertEquals("Game of Thrones", rememberedTickTitle(episode, "Game of Thrones", episode, "Old Name"))
    }
}
