package com.codingpit.muviss.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual fun platformIoDispatcher(): CoroutineDispatcher = Dispatchers.Default
