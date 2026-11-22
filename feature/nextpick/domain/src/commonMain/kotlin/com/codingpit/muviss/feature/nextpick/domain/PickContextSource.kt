package com.codingpit.muviss.feature.nextpick.domain

/**
 * Reads what a Pick Session needs before its first question (EPIC 51): connectivity, whether there is a
 * backlog, whether anything is rewatchable, and the length the person chose. The UI asks for it through
 * this interface so it never depends on the data module.
 */
fun interface PickContextSource {
    suspend fun read(): PickContext
}
