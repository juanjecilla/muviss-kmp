package com.codingpit.muviss.feature.settings.data

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.codingpit.muviss.core.database.MuvissDatabase

/**
 * Restores the Display Preferences group and the Muted Genres rows (ADR 0036, #642). Kept apart from
 * [SqlDelightBackupRestorer], which is already the size its row-by-row merge needs.
 *
 * The group is one unit with one stamp. A file that carries the stamp takes the backup's group when that
 * stamp is newer than the local one (a null local stamp loses to any backup stamp; a tie keeps local). A
 * legacy file has no stamp, so the group fills field by field over first-run defaults, the way it always
 * did. When that fill changes something, the group becomes dirty: stamp `0` if the device has no stamp
 * (older than anything on the account, the rule `26.sqm`'s backfill uses), otherwise the stamp it has.
 *
 * Device switches are never written here. Callers run this inside the restore's transaction.
 */
internal class DisplayPreferencesRestorer(private val database: MuvissDatabase) {

    /** The ten Display Preferences, as `appSettings` holds them. Equality is what "the fill changed nothing" means. */
    private data class Group(
        val theme: String,
        val language: String,
        val region: String,
        val animationsEnabled: Boolean,
        val triageDeckAnimations: Boolean,
        val tickAnimations: Boolean,
        val triageControlScheme: String,
        val triageSnoozePeriod: String,
        val triageSnoozePlacement: String,
        val mutedGenresInSearch: Boolean,
    )

    suspend fun restoreGroup(backup: SettingsExport) {
        val queries = database.appSettingsQueries
        queries.ensureRow()
        val local = queries.selectSettings().awaitAsOneOrNull() ?: return
        val localGroup = Group(
            theme = local.theme,
            language = local.language,
            region = local.region,
            animationsEnabled = local.animationsEnabled,
            triageDeckAnimations = local.triageDeckAnimations,
            tickAnimations = local.tickAnimations,
            triageControlScheme = local.triageControlScheme,
            triageSnoozePeriod = local.triageSnoozePeriod,
            triageSnoozePlacement = local.triageSnoozePlacement,
            mutedGenresInSearch = local.mutedGenresInSearch,
        )
        val backupStamp = backup.displayPreferencesUpdatedAtEpochMs
        if (backupStamp != null) {
            if (!wins(backupStamp, local.displayPreferencesUpdatedAtEpochMs)) return
            writeGroup(backup.toGroup(), stamp = backupStamp)
        } else {
            val merged = Group(
                theme = if (localGroup.theme == DEFAULT_THEME) backup.theme else localGroup.theme,
                language = if (localGroup.language == DEFAULT_LANGUAGE) backup.language else localGroup.language,
                region = if (localGroup.region == DEFAULT_REGION) backup.region else localGroup.region,
                animationsEnabled = if (localGroup.animationsEnabled) backup.animationsEnabled else localGroup.animationsEnabled,
                triageDeckAnimations = if (localGroup.triageDeckAnimations) backup.triageDeckAnimations else localGroup.triageDeckAnimations,
                tickAnimations = if (localGroup.tickAnimations) backup.tickAnimations else localGroup.tickAnimations,
                triageControlScheme = if (localGroup.triageControlScheme == DEFAULT_SCHEME) backup.triageControlScheme else localGroup.triageControlScheme,
                triageSnoozePeriod = if (localGroup.triageSnoozePeriod == DEFAULT_SNOOZE_PERIOD) backup.triageSnoozePeriod else localGroup.triageSnoozePeriod,
                triageSnoozePlacement = if (localGroup.triageSnoozePlacement == DEFAULT_SNOOZE_PLACEMENT) backup.triageSnoozePlacement else localGroup.triageSnoozePlacement,
                mutedGenresInSearch = if (!localGroup.mutedGenresInSearch) backup.mutedGenresInSearch else localGroup.mutedGenresInSearch,
            )
            if (merged != localGroup) {
                writeGroup(merged, stamp = local.displayPreferencesUpdatedAtEpochMs ?: 0L)
            }
        }
        // The legacy list (ADR 0031) is restored over its default only, as it always was. A file that carries
        // the new fields has the same string, derived from the decisions, so it is harmless on either path.
        if (local.mutedGenres == DEFAULT_MUTED_GENRES) queries.updateMutedGenres(backup.mutedGenres)
    }

    /**
     * The Muted Genres choice marker (ADR 0036). Takes the backup's marker when the device has none, and
     * otherwise keeps whichever stamp is newer. Written dirty with the backup's stamp, never the clock.
     */
    suspend fun restoreChoice(row: MutedGenreChoiceExport) {
        val queries = database.mutedGenreChoiceQueries
        val local = queries.select().awaitAsOneOrNull()
        if (!wins(row.updatedAtEpochMs, local?.updatedAtEpochMs)) return
        queries.upsert(defaultsAtChoice = row.defaultsAtChoice, updatedAtEpochMs = row.updatedAtEpochMs, isDirty = true)
    }

    /**
     * One Muted Genres decision, merged per row on its stamp like every other row (ties keep local). A
     * restored decision is dirty, so it reaches the account; a tombstone restores as a tombstone.
     */
    suspend fun restoreDecision(row: MutedGenreExport): Boolean {
        val queries = database.mutedGenreQueries
        val genreId = row.genreId.toLong()
        val local = queries.selectOne(mediaType = row.mediaType, genreId = genreId).awaitAsOneOrNull()
        if (!wins(row.updatedAtEpochMs, local?.updatedAtEpochMs)) return false
        queries.upsert(
            mediaType = row.mediaType,
            genreId = genreId,
            name = row.name,
            deleted = row.deleted,
            isDirty = true,
            updatedAtEpochMs = row.updatedAtEpochMs,
        )
        return true
    }

    private suspend fun writeGroup(group: Group, stamp: Long) {
        database.appSettingsQueries.restoreDisplayPreferences(
            theme = group.theme,
            language = group.language,
            region = group.region,
            animationsEnabled = group.animationsEnabled,
            triageDeckAnimations = group.triageDeckAnimations,
            tickAnimations = group.tickAnimations,
            triageControlScheme = group.triageControlScheme,
            triageSnoozePeriod = group.triageSnoozePeriod,
            triageSnoozePlacement = group.triageSnoozePlacement,
            mutedGenresInSearch = group.mutedGenresInSearch,
            stampEpochMs = stamp,
            isDirty = true,
        )
    }

    private fun SettingsExport.toGroup() = Group(
        theme = theme,
        language = language,
        region = region,
        animationsEnabled = animationsEnabled,
        triageDeckAnimations = triageDeckAnimations,
        tickAnimations = tickAnimations,
        triageControlScheme = triageControlScheme,
        triageSnoozePeriod = triageSnoozePeriod,
        triageSnoozePlacement = triageSnoozePlacement,
        mutedGenresInSearch = mutedGenresInSearch,
    )

    private companion object {
        // A fresh `appSettings` row, as `ensureRow` seeds it. Mirrors AppSettings.sq: a field equal to
        // its seed has not been chosen, so a legacy restore may fill it.
        const val DEFAULT_THEME = "SYSTEM"
        const val DEFAULT_LANGUAGE = ""
        const val DEFAULT_REGION = "US"
        const val DEFAULT_SCHEME = "FOUR_WAY"
        const val DEFAULT_SNOOZE_PERIOD = "ONE_WEEK"
        const val DEFAULT_SNOOZE_PLACEMENT = "MIXED_IN"
        const val DEFAULT_MUTED_GENRES = "tv:10767:Talk;tv:10763:News;tv:10764:Reality"
    }
}

/** True when [incoming] should replace a local row stamped [localUpdatedAt] (null: there is none). Ties keep local. */
internal fun wins(incoming: Long, localUpdatedAt: Long?): Boolean = localUpdatedAt == null || incoming > localUpdatedAt
