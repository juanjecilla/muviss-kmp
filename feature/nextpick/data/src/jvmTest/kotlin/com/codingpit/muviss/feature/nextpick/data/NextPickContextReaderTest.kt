package com.codingpit.muviss.feature.nextpick.data

import com.codingpit.muviss.core.common.connectivity.ConnectivityMonitor
import com.codingpit.muviss.feature.nextpick.api.NextPickPreferences
import com.codingpit.muviss.feature.nextpick.api.ResultMode
import com.codingpit.muviss.feature.nextpick.api.SessionLength
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class NextPickContextReaderTest {

    /** The session length the person stored in Settings (#657). */
    private class StoredPreferences(length: SessionLength) : NextPickPreferences {
        override val sessionLength: Flow<SessionLength> = flowOf(length)
        override val resultMode: Flow<ResultMode> = flowOf(ResultMode.ONE_PICK)

        override suspend fun setSessionLength(length: SessionLength) = Unit

        override suspend fun setResultMode(mode: ResultMode) = Unit
    }

    @Test
    fun a_stored_quick_session_opens_with_the_quick_length() = runTest {
        val context = NextPickContextReader(ConnectivityMonitor.AlwaysOnline, { FakeLibrary(emptyList()) }, StoredPreferences(SessionLength.QUICK), { FakeTasteProfile() }).read()

        assertEquals(SessionLength.QUICK, context.length)
    }

    @Test
    fun a_stored_no_questions_session_opens_with_no_questions() = runTest {
        val context = NextPickContextReader(ConnectivityMonitor.AlwaysOnline, { FakeLibrary(emptyList()) }, StoredPreferences(SessionLength.NONE), { FakeTasteProfile() }).read()

        assertEquals(SessionLength.NONE, context.length)
    }
}
