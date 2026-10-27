package com.codingpit.muviss.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import com.codingpit.muviss.feature.settings.api.TasteKind
import com.codingpit.muviss.feature.settings.ui.generated.resources.Res
import com.codingpit.muviss.feature.settings.ui.generated.resources.taste_company_alone
import com.codingpit.muviss.feature.settings.ui.generated.resources.taste_company_family
import com.codingpit.muviss.feature.settings.ui.generated.resources.taste_company_partner
import com.codingpit.muviss.feature.settings.ui.generated.resources.taste_format_limited
import com.codingpit.muviss.feature.settings.ui.generated.resources.taste_format_movie
import com.codingpit.muviss.feature.settings.ui.generated.resources.taste_format_series
import com.codingpit.muviss.feature.settings.ui.generated.resources.taste_genres_offline
import com.codingpit.muviss.feature.settings.ui.generated.resources.taste_muted_hint
import com.codingpit.muviss.feature.settings.ui.generated.resources.taste_profile_body
import com.codingpit.muviss.feature.settings.ui.generated.resources.taste_profile_title
import com.codingpit.muviss.feature.settings.ui.generated.resources.taste_section_company
import com.codingpit.muviss.feature.settings.ui.generated.resources.taste_section_formats
import com.codingpit.muviss.feature.settings.ui.generated.resources.taste_section_genres_movies
import com.codingpit.muviss.feature.settings.ui.generated.resources.taste_section_genres_tv
import com.codingpit.muviss.feature.settings.ui.generated.resources.taste_section_languages
import com.codingpit.muviss.feature.settings.ui.generated.resources.taste_section_services
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

const val TASTE_PROFILE_LIST_TAG = "tasteProfileList"

/** Test tag of one chip, `tasteOption-<KIND>-<value>`, so a test can find and tap a specific item. */
fun tasteOptionTag(option: TasteOption): String = "tasteOption-${option.item.id}"

@Composable
fun TasteProfileScreen(viewModel: TasteProfileViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TasteProfileContent(state = state, onToggle = viewModel::onToggle)
}

/** Stateless so screenshots and tests render exactly what ships. */
@Composable
fun TasteProfileContent(state: TasteProfileUiState, onToggle: (TasteOption) -> Unit) {
    if (state.loading) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(Modifier.padding(top = MuvissSpacing.xxl))
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = MuvissSpacing.l).testTag(TASTE_PROFILE_LIST_TAG),
        verticalArrangement = Arrangement.spacedBy(MuvissSpacing.xs),
    ) {
        item {
            Text(
                stringResource(Res.string.taste_profile_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = MuvissSpacing.l, bottom = MuvissSpacing.xs),
            )
            Text(stringResource(Res.string.taste_profile_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        chipSection(Res.string.taste_section_genres_movies, state.movieGenres, onToggle)
        chipSection(Res.string.taste_section_genres_tv, state.tvGenres, onToggle)
        if (state.genresFailed) {
            item { Text(stringResource(Res.string.taste_genres_offline), style = MaterialTheme.typography.bodyMedium) }
        }
        if (state.movieGenres.any { it.muted } || state.tvGenres.any { it.muted }) {
            item { Text(stringResource(Res.string.taste_muted_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        chipSection(Res.string.taste_section_formats, state.formats, onToggle)
        chipSection(Res.string.taste_section_services, state.services, onToggle)
        chipSection(Res.string.taste_section_languages, state.languages, onToggle)
        chipSection(Res.string.taste_section_company, state.companies, onToggle)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.chipSection(
    header: StringResource,
    options: List<TasteOption>,
    onToggle: (TasteOption) -> Unit,
) {
    if (options.isEmpty()) return
    item {
        Text(
            stringResource(header),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = MuvissSpacing.m),
        )
    }
    item {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.s)) {
            options.forEach { option -> TasteChip(option, onToggle) }
        }
    }
}

@Composable
private fun TasteChip(option: TasteOption, onToggle: (TasteOption) -> Unit) {
    // Genres, services and languages carry their own name; only the keyed kinds need a localized one.
    val label = keyedName(option)?.let { stringResource(it) } ?: option.item.label
    FilterChip(
        selected = option.liked,
        // A muted genre is disabled, not hidden: the person can see why it is not on offer (ADR 0031).
        enabled = !option.muted,
        onClick = { onToggle(option) },
        label = { Text(label) },
        modifier = Modifier.testTag(tasteOptionTag(option)),
    )
}

/** The localized name of a format or company, which are stored as keys. Null for every other kind, which show their own label. */
private fun keyedName(option: TasteOption): StringResource? = when (option.item.kind) {
    TasteKind.FORMAT -> when (option.item.value) {
        "SERIES" -> Res.string.taste_format_series
        "LIMITED_SERIES" -> Res.string.taste_format_limited
        else -> Res.string.taste_format_movie
    }

    TasteKind.COMPANY -> when (option.item.value) {
        "PARTNER" -> Res.string.taste_company_partner
        "FAMILY" -> Res.string.taste_company_family
        else -> Res.string.taste_company_alone
    }

    TasteKind.GENRE, TasteKind.SERVICE, TasteKind.LANGUAGE -> null
}
