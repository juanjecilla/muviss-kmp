package com.codingpit.muviss.core.designsystem.share

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/** Desktop has no share sheet: copy only. */
private class DesktopTextSharer : TextSharer {
    override val canShare: Boolean = false

    override fun copy(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    override fun share(text: String) = copy(text)
}

@Composable
actual fun rememberTextSharer(): TextSharer = remember { DesktopTextSharer() }
