package com.codingpit.muviss.core.database

/**
 * No OS-side exclusion on this platform (ADR 0034). [DeviceBackupExclusion.apply] is a no-op here: the Settings
 * row and Onboarding page are not offered where backups are not, and on Android the backup agent reads the stored flag itself.
 */
actual object DeviceBackupExclusion {
    actual fun apply(enabled: Boolean) = Unit
}
