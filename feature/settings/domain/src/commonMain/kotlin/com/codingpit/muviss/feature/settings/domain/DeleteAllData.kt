package com.codingpit.muviss.feature.settings.domain

/**
 * Settings > Delete all data (EPIC 29, #72): the reset that used to need an
 * uninstall. Everything the person made goes — library, ticks, rewatches,
 * lists, triage, snoozes, profile — along with every setting and every cache,
 * so the app afterwards is a fresh install's.
 *
 * It is local. A signed-in sync account stays signed in and its server copy
 * is untouched; the sync cursors are forgotten, so the next sync is a first
 * one and brings that copy back. Sync is off in release builds (ADR 0018).
 */
interface LocalDataEraser {
    suspend fun deleteAllData()
}

class DeleteAllDataUseCase(private val eraser: LocalDataEraser) {
    suspend operator fun invoke() = eraser.deleteAllData()
}
