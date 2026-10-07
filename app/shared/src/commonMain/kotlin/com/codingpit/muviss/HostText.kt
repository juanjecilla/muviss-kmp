package com.codingpit.muviss

import androidx.compose.runtime.Composable
import com.codingpit.muviss.app.shared.generated.resources.Res
import com.codingpit.muviss.app.shared.generated.resources.menu_file
import com.codingpit.muviss.app.shared.generated.resources.menu_quit
import com.codingpit.muviss.app.shared.generated.resources.menu_view
import org.jetbrains.compose.resources.stringResource

/**
 * Copy the platform hosts draw outside `MuvissApp()` — desktop's menu bar
 * (#222). Public because `:app:shared`'s `Res` is internal to it, like every
 * module's.
 */
object HostText {
    @Composable
    fun menuFile(): String = stringResource(Res.string.menu_file)

    @Composable
    fun menuView(): String = stringResource(Res.string.menu_view)

    @Composable
    fun menuQuit(): String = stringResource(Res.string.menu_quit)
}
