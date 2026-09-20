package com.codingpit.muviss.core.sync

/**
 * [SyncAvailability] as the build's generated constants say it: sync is
 * present when `SYNC_ENABLED` and both Supabase keys are, and may run without
 * being asked only when `SYNC_BACKGROUND_ENABLED` is set as well.
 */
internal object BuildSyncAvailability : SyncAvailability {
    override fun isConfigured(): Boolean = MuvissBuildConfig.SYNC_ENABLED &&
        MuvissBuildConfig.SUPABASE_URL.isNotBlank() &&
        MuvissBuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    override fun isBackgroundAvailable(): Boolean = isConfigured() && MuvissBuildConfig.SYNC_BACKGROUND_ENABLED
}
