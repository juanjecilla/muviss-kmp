package com.codingpit.muviss.backup

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException

/**
 * Android's backup agent (Device Backup, ADR 0034). It is the only thing that decides what leaves the device
 * through Google's backup and device-to-device transfer, and it is an **allowlist**: the library database goes in
 * when the person turned Device Backup on, and **nothing else goes in**. KSafe's DataStore (the Own TMDB Key and
 * the rest of the secrets), shared preferences and every other file are never offered, so they are not "excluded
 * by a rule that could drift" but simply never handed to the framework.
 *
 * The agent may run in **restricted mode** (the system creates this class without the app's `Application`) or next
 * to a running app, so there is no Koin graph to rely on and it must not reach for one. It reads the stored choice straight from the database file,
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

        // One connection for the whole copy. The checkpoint folds the log into the main file, then an
        // IMMEDIATE transaction holds SQLite's write lock while the files are copied: whether the system runs
        // this agent restricted or alongside the app's own connections, no write can land between copying
        // muviss.db and copying its -wal, so the pair restores as one consistent state. Readers are not
        // blocked; writers wait out SQLite's busy timeout. If the lock cannot be had, the backup run fails
        // rather than offering a torn copy (the previous backup stays in place).
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            runCatching { db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() } }
            try {
                db.beginTransactionNonExclusive()
            } catch (e: SQLiteException) {
                throw IOException("the library database is busy; not offering a torn copy", e)
            }
            try {
                // -shm is left out on purpose: it is an index SQLite rebuilds from the WAL, and a restored copy
                // of an index from another device describes nothing that device has.
                listOf(database, File("${database.path}-wal"))
                    .filter { it.exists() }
                    .forEach { fullBackupFile(it, data) }
            } finally {
                db.endTransaction() // nothing was written: this only releases the lock
            }
        }
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

    private companion object {
        /** Matches `AndroidSqliteDriver(..., "muviss.db")` in `:core:database`. */
        const val DATABASE_NAME = "muviss.db"
    }
}
