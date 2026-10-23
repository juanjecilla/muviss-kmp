package com.codingpit.muviss.core.database

import app.cash.sqldelight.db.SqlDriver

/**
 * Device Backup (ADR 0034): tells the OS whether this device's library may be copied into its own backups
 * (iCloud or a device backup on iOS; Google's backup and device transfer on Android, which decide through
 * `MuvissBackupAgent` instead, from the same stored flag).
 *
 * The default is **excluded**: a library nobody asked to back up is not backed up. The stored flag is
 * `appSettings.deviceBackupEnabled`, written by `SettingsRepository.setDeviceBackupEnabled`, which calls [apply]
 * with the same value, and read again by [DatabaseDriverFactory] when it opens the database, so the OS state is
 * re-established on every launch, not only on the toggle.
 */
expect object DeviceBackupExclusion {
    /**
     * Includes the library in the OS backup when [enabled], excludes it when not. Idempotent. Throws when the
     * OS refuses the change, so a caller can keep the stored choice and the OS state from disagreeing.
     */
    fun apply(enabled: Boolean)
}

/** The stored Device Backup choice; a missing row is the default, off. */
internal fun storedDeviceBackupEnabled(driver: SqlDriver): Boolean = MuvissDatabase(driver)
    .appSettingsQueries
    .selectSettings()
    .executeAsOneOrNull()
    ?.deviceBackupEnabled
    ?: false
