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
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import com.codingpit.muviss.feature.search.domain.ExternalLink
import com.codingpit.muviss.feature.search.domain.ExternalLinkKind
import com.codingpit.muviss.feature.search.ui.generated.resources.Res
import com.codingpit.muviss.feature.search.ui.generated.resources.link_imdb
import com.codingpit.muviss.feature.search.ui.generated.resources.link_tmdb
import com.codingpit.muviss.feature.search.ui.generated.resources.link_wikipedia
import com.codingpit.muviss.feature.search.ui.generated.resources.link_wikipedia_english
import org.jetbrains.compose.resources.stringResource

internal const val EXTERNAL_LINKS_TAG = "externalLinks"

/**
 * The language a screen's Wikipedia link opens in when no language is passed, for tests only: the TMDB default
 * (`DefaultMetadataLocale`). The app shell always passes the app's content language, read from `MetadataLocale`,
 * the same value every TMDB request on the screen was made with, through `searchSection`'s `linkLanguage`.
 * It is not the device locale: a Spanish device with the app set to English gets the English article.
 */
internal const val DEFAULT_LINK_LANGUAGE = "en-US"

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
    ExternalLinkKind.WIKIPEDIA_ENGLISH -> Res.string.link_wikipedia_english
    ExternalLinkKind.TMDB -> Res.string.link_tmdb
}

/** Opens a URL in the system browser. A device with no browser to hand it to must not crash the screen. */
@Composable
private fun rememberLinkOpener(): (String) -> Unit {
    val handler = LocalUriHandler.current
    return { url -> runCatching { handler.openUri(url) } }
}
