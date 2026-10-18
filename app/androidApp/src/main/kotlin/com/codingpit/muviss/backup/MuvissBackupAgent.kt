package com.codingpit.muviss.backup

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.database.sqlite.SQLiteDatabase
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Android's backup agent (Device Backup, ADR 0034). It is the only thing that decides what leaves the device
 * through Google's backup and device-to-device transfer, and it is an **allowlist**: the library database goes in
 * when the person turned Device Backup on, and **nothing else goes in**. KSafe's DataStore (the Own TMDB Key and
 * the rest of the secrets), shared preferences and every other file are never offered, so they are not "excluded
 * by a rule that could drift" but simply never handed to the framework.
 *
 * The agent runs in **restricted mode**: the system creates this class without the app's `Application`, so there
 * is no Koin graph here and it must not reach for one. It reads the stored choice straight from the database file,
 * as [DeviceBackupDecider] documents. It is also the same class the system instantiates by name, which is why
 * R8 keeps it (see `proguard-rules.pro`).
 *
 * The key-value half of the backup API is unused (`fullBackupOnly` in the manifest), so [onBackup] and
 * [onRestore] do nothing. Restore is the framework's default file restore, which writes the database back in
 * place before the app starts.
 */
class MuvissBackupAgent : BackupAgent() {

    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput, newState: ParcelFileDescriptor?) = Unit

    override fun onRestore(data: BackupDataInput, appVersionCode: Int, newState: ParcelFileDescriptor?) = Unit

    override fun onFullBackup(data: FullBackupDataOutput) {
        val database = getDatabasePath(DATABASE_NAME)
        if (!DeviceBackupDecider.includesLibrary(readStoredFlag(database))) return

        checkpointQuietly(database)
        // -shm is left out on purpose: it is an index SQLite rebuilds from the WAL, and a restored copy of an
        // index from another device describes nothing that device has.
        listOf(database, File("${database.path}-wal"))
            .filter { it.exists() }
            .forEach { fullBackupFile(it, data) }
    }

    /**
     * Opens the file read-only and asks for `appSettings.deviceBackupEnabled`. Any failure (no file yet, no row,
     * a database that predates the column, a locked file) is `null`, which the decider reads as off.
     */
    private fun readStoredFlag(database: File): Boolean? {
        if (!database.exists()) return null
        return runCatching {
            SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT deviceBackupEnabled FROM appSettings WHERE id = 0", null).use { cursor ->
                    if (cursor.moveToFirst()) DeviceBackupDecider.flagFromColumn(cursor.getLong(0)) else null
                }
            }
        }.getOrNull()
    }

    /**
     * Folds the write-ahead log into the main file so the copy is complete without it. Best effort: a busy
     * database keeps its log, and [onFullBackup] then copies the `-wal` as well.
     */
    private fun checkpointQuietly(database: File) {
        runCatching {
            SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            }
        }
    }

    private companion object {
        /** Matches `AndroidSqliteDriver(..., "muviss.db")` in `:core:database`. */
        const val DATABASE_NAME = "muviss.db"
    }
}
