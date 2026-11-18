package com.codingpit.muviss.feature.search.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.intl.Locale
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import com.codingpit.muviss.feature.search.domain.ExternalLink
import com.codingpit.muviss.feature.search.domain.ExternalLinkKind
import com.codingpit.muviss.feature.search.ui.generated.resources.Res
import com.codingpit.muviss.feature.search.ui.generated.resources.link_imdb
import com.codingpit.muviss.feature.search.ui.generated.resources.link_tmdb
import com.codingpit.muviss.feature.search.ui.generated.resources.link_wikipedia
import org.jetbrains.compose.resources.stringResource

internal const val EXTERNAL_LINKS_TAG = "externalLinks"

/** The language links are opened in: the one the screen is already showing. */
@Composable
internal fun linkLanguage(): String = Locale.current.language

/**
 * Links out to IMDb, Wikipedia and TMDB. They open in the system browser and Muviss sends nothing
 * of its own (`docs/PRIVACY.md`). Draws nothing when there is no link to offer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExternalLinksRow(links: List<ExternalLink>, modifier: Modifier = Modifier, open: (String) -> Unit = rememberLinkOpener()) {
    if (links.isEmpty()) return
    FlowRow(modifier.testTag(EXTERNAL_LINKS_TAG), horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.s)) {
        links.forEach { link ->
            AssistChip(onClick = { open(link.url) }, label = { Text(stringResource(link.kind.label())) })
        }
    }
}

private fun ExternalLinkKind.label() = when (this) {
    ExternalLinkKind.IMDB -> Res.string.link_imdb
    ExternalLinkKind.WIKIPEDIA -> Res.string.link_wikipedia
    ExternalLinkKind.TMDB -> Res.string.link_tmdb
}

/** Opens a URL in the system browser. A device with no browser to hand it to must not crash the screen. */
@Composable
private fun rememberLinkOpener(): (String) -> Unit {
    val handler = LocalUriHandler.current
    return { url -> runCatching { handler.openUri(url) } }
}
