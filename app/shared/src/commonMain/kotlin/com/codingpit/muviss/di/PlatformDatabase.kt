package com.codingpit.muviss.di

import androidx.compose.runtime.Composable
import com.codingpit.muviss.core.database.DatabaseDriverFactory

/**
 * Builds the platform [DatabaseDriverFactory]. Android needs a `Context` read
 * from the composition; every other target constructs it with no arguments.
 */
@Composable
expect fun rememberDatabaseDriverFactory(): DatabaseDriverFactory
