package com.codingpit.muviss.core.sync

/**
 * Erases this device's local library once its account has been deleted on the
 * server (ADR 0019, "Deleting the account").
 *
 * `:core:sync` cannot call the settings feature's eraser directly (ADR 0004 — a
 * core module does not depend on a feature), so this is the seam, the same
 * indirection [TitleRefresher] uses. `:app:shared` binds the real
 * implementation over `DeleteAllDataUseCase`, which means deleting the account
 * does exactly what Settings' "Delete all data" does, through the one eraser.
 *
 * Unlike [TitleRefresher] this one can fail, and the engine reports it: a
 * library that survived a deleted account is not a successful deletion.
 */
fun interface AccountLocalDataEraser {
    suspend fun eraseLocalData()
}

/** The default binding `:core:sync` ships on its own, and every test that does not exercise erasure. */
object NoOpAccountLocalDataEraser : AccountLocalDataEraser {
    override suspend fun eraseLocalData() = Unit
}
