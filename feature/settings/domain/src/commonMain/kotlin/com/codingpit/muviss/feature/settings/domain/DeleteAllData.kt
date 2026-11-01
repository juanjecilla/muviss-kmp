package com.codingpit.muviss.feature.settings.domain

/**
 * Settings > Delete all data (EPIC 29, #72): the reset that used to need an
 * uninstall. Everything the person made goes — library, ticks, rewatches,
 * lists, triage, snoozes, profile — along with every setting and every cache,
 * so the app afterwards is a fresh install's.
 *
 * It is local, and a signed-in sync account is signed out first (#645): the
 * session ends before anything is erased, so no sync can pull the library back.
 * The server copy is untouched; signing in again is a first sync and brings
 * it back. Sync is off in release builds (ADR 0018).
 */
interface LocalDataEraser {
    suspend fun deleteAllData()
}

class DeleteAllDataUseCase(private val eraser: LocalDataEraser) {
    suspend operator fun invoke() = eraser.deleteAllData()
}
