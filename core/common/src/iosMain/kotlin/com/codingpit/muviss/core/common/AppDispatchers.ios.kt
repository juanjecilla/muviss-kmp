package com.codingpit.muviss.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/**
 * Blocking I/O (SQLite, file and network reads) belongs on the IO pool, as on
 * Android and JVM. `Default` is sized to the CPU count and blocking it starves
 * unrelated CPU work.
 */
actual fun platformIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
