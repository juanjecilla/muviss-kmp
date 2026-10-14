package com.codingpit.muviss.feature.settings.domain

/**
 * What a Muviss backup holds, shown before it is restored (EPIC 29, #72).
 * [formatVersion] 1 is a file from before backups were versioned.
 */
data class BackupSummary(
    val formatVersion: Int,
    val exportedAtEpochMs: Long,
    val titleCount: Int,
    val episodeCount: Int,
    val listCount: Int,
)

/**
 * What a restore did. A row the device already had a newer copy of is [kept]
 * rather than overwritten — the same last-write-wins rule sync applies — which
 * is what makes restoring an old backup over a library in use safe.
 */
data class RestoreResult(val restored: Int, val kept: Int)

/**
 * Restores a file Muviss's own export wrote (see [ImportFormatDetector.isMuvissBackup]).
 *
 * Unlike the Trakt/TV Time/CSV importers this needs no network: the backup
 * already holds Muviss's own ids and snapshots, so it writes rows back rather
 * than resolving titles. Both calls throw [ImportFileException] for a file
 * that is not a readable backup, or one written by a newer version.
 */
interface BackupRestorer {
    suspend fun summarize(content: String): BackupSummary

    suspend fun restore(content: String): RestoreResult
}
