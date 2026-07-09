package com.codingpit.muviss.di

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.codingpit.muviss.core.database.DatabaseDriverFactory

@Composable
actual fun rememberDatabaseDriverFactory(): DatabaseDriverFactory = remember { DatabaseDriverFactory() }
