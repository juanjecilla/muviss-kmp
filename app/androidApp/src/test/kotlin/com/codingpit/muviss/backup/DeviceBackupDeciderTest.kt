package com.codingpit.muviss.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The rule Google's backup and device transfer follow for the library (ADR 0034). Pure, so no device or Robolectric. */
class DeviceBackupDeciderTest {

    @Test
    fun `only an explicit on includes the library`() {
        assertTrue(DeviceBackupDecider.includesLibrary(storedFlag = true))
        assertFalse(DeviceBackupDecider.includesLibrary(storedFlag = false))
    }

    @Test
    fun `an unreadable choice is off, never on`() {
        // No file yet, no row, a database that predates the column, or a locked file all come back as null.
        assertFalse(DeviceBackupDecider.includesLibrary(storedFlag = null))
    }

    @Test
    fun `a sqlite integer column reads any non-zero value as on`() {
        assertEquals(true, DeviceBackupDecider.flagFromColumn(1L))
        assertEquals(false, DeviceBackupDecider.flagFromColumn(0L))
    }

    @Test
    fun `a missing column value is not a choice at all`() {
        assertNull(DeviceBackupDecider.flagFromColumn(null))
    }

    @Test
    fun `a missing row, read through the column rule, still ends up off`() {
        val decided = DeviceBackupDecider.includesLibrary(DeviceBackupDecider.flagFromColumn(null))
        assertFalse(decided)
    }
}
