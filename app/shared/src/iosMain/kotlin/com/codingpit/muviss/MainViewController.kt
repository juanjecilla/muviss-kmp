package com.codingpit.muviss

import androidx.compose.ui.window.ComposeUIViewController

// Named in PascalCase to read as a UIViewController factory from Swift.
@Suppress("FunctionNaming")
fun MainViewController() = ComposeUIViewController { MuvissApp() }
