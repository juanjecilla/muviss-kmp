package com.codingpit.muviss.core.common.connectivity

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ReconnectionsTest {
    private fun monitor(vararg states: Boolean) = object : ConnectivityMonitor {
        override val isOnline: Flow<Boolean> = flowOf(*states.toTypedArray())
    }

    @Test
    fun subscribing_while_online_is_not_a_reconnection() = runTest {
        assertEquals(0, monitor(true, true).reconnections().toList().size)
    }

    @Test
    fun each_offline_to_online_edge_is_one_reconnection() = runTest {
        assertEquals(2, monitor(true, false, true, true, false, false, true).reconnections().toList().size)
    }

    @Test
    fun starting_offline_then_coming_online_counts() = runTest {
        assertEquals(1, monitor(false, true).reconnections().toList().size)
    }
}
