package com.codingpit.muviss.feature.nextpick.domain

import com.codingpit.muviss.feature.nextpick.api.ResultMode
import com.codingpit.muviss.feature.nextpick.api.SessionLength
import kotlin.test.Test
import kotlin.test.assertEquals

class NextPickResultModeTest {

    @Test
    fun each_stored_result_mode_maps_to_its_presentation() {
        assertEquals(NextPickResultMode.ONE_PICK, ResultMode.ONE_PICK.presentation())
        assertEquals(NextPickResultMode.TOP_PICKS, ResultMode.TOP_PICKS.presentation())
        assertEquals(NextPickResultMode.DECK, ResultMode.DECK.presentation())
    }

    @Test
    fun the_default_session_length_is_standard_as_the_settings_copy_says() {
        assertEquals(SessionLength.STANDARD, SessionLength.DEFAULT)
        assertEquals(SessionLength.STANDARD, PickContext().length)
    }
}
