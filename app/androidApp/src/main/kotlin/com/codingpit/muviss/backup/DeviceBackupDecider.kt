package com.codingpit.muviss.backup

/**
 * Whether Android's own backup (Google's cloud backup, or a device-to-device transfer) may carry the library
 * (Device Backup, ADR 0034). Pure, so the rule is pinned by a JVM test rather than by a device.
 *
 * Only an explicit "on" includes it. A missing row (a database that predates the setting, or one the agent
 * could not read) is "off": the OS backs nothing up that the person did not ask for.
 */
internal object DeviceBackupDecider {

    /** [storedFlag] is `appSettings.deviceBackupEnabled`, or null when it could not be read. */
    fun includesLibrary(storedFlag: Boolean?): Boolean = storedFlag == true

    /** Reads the SQLite integer column: any non-zero value is on, and a missing value is not a choice at all. */
    fun flagFromColumn(value: Long?): Boolean? = value?.let { it != 0L }
}
