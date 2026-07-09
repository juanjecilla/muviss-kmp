package com.codingpit.muviss.di

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.codingpit.muviss.core.database.DatabaseDriverFactory

@Composable
actual fun rememberDatabaseDriverFactory(): DatabaseDriverFactory {
    val context = LocalContext.current
    return remember(context) { DatabaseDriverFactory(context) }
}
