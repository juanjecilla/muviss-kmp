@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.codingpit.muviss.core.database

import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey

/**
 * iOS (ADR 0034). `NSURLIsExcludedFromBackupKey` is set on the database's **directory** and, where they exist,
 * on `muviss.db`, `-wal` and `-shm`. The directory flag is the one that holds: SQLite deletes and recreates the
 * `-wal`/`-shm` files while the app runs, and a freshly created file does not inherit a per-file flag. Excluding
 * a directory excludes its contents from the backup, so a recreated sidecar stays out.
 *
 * Excluding the sidecars but not the main file (or the reverse) is the failure to avoid: a backup that holds
 * `muviss.db` without its `-wal` restores a library missing the most recent commits.
 */
actual object DeviceBackupExclusion {
    actual fun apply(enabled: Boolean) {
        val directory = databaseDirectory()
        applyTo(directory, enabled, ownDirectory = directory == preferredDatabaseDirectory())
    }
}

/**
 * Sets the exclusion for [directory] and its database files. Internal so the iOS test can aim it at a temp
 * directory. The directory flag is set only on the database's [ownDirectory]: while a failed move leaves the
 * database in an older place (the App Group container's root, or Documents), flagging that directory would
 * exclude everything else stored there, so it gets the per-file flags and any directory flag is cleared.
 */
internal fun applyTo(directory: String, enabled: Boolean, ownDirectory: Boolean = true) {
    val excluded = !enabled
    setExcluded(directory, excluded && ownDirectory)
    listOf("", "-wal", "-shm").forEach { suffix ->
        val path = "$directory/$DATABASE_FILE_NAME$suffix"
        if (NSFileManager.defaultManager.fileExistsAtPath(path)) setExcluded(path, excluded)
    }
}

/** Whether [path] carries the exclusion itself (not through a parent directory). */
internal fun isExcludedFromBackup(path: String): Boolean {
    val values = NSURL.fileURLWithPath(path).resourceValuesForKeys(listOf(NSURLIsExcludedFromBackupKey), error = null)
    return (values?.get(NSURLIsExcludedFromBackupKey) as? NSNumber)?.boolValue ?: false
}

private fun setExcluded(path: String, excluded: Boolean) {
    NSURL.fileURLWithPath(path).setResourceValue(NSNumber(bool = excluded), forKey = NSURLIsExcludedFromBackupKey, error = null)
}
