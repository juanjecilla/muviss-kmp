package com.codingpit.muviss.core.common

/**
 * The running app's version, for the Settings "About" screen. Backed by
 * [MuvissBuildConfig], generated at build time from git (see
 * `core/common/build.gradle.kts`) — the same git-derived value
 * `:app:androidApp` uses for its own `versionCode`/`versionName`, just made
 * reachable from a plain KMP module instead of Android's BuildConfig/
 * PackageManager.
 */
data class AppVersion(
    val versionName: String,
    val versionCode: Long,
) {
    companion object {
        val current: AppVersion = AppVersion(MuvissBuildConfig.APP_VERSION_NAME, MuvissBuildConfig.APP_VERSION_CODE)
    }
}
