package com.codingpit.muviss.feature.search.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codingpit.muviss.core.designsystem.component.CarouselHeader
import com.codingpit.muviss.core.designsystem.component.ErrorState
import com.codingpit.muviss.core.designsystem.component.PosterCard
import com.codingpit.muviss.core.designsystem.component.PosterImage
import com.codingpit.muviss.core.designsystem.component.PosterSize
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import com.codingpit.muviss.feature.search.domain.Filmography
import com.codingpit.muviss.feature.search.domain.FilmographyEntry
import com.codingpit.muviss.feature.search.ui.generated.resources.Res
import com.codingpit.muviss.feature.search.ui.generated.resources.crew
import com.codingpit.muviss.feature.search.ui.generated.resources.person_bio_less
import com.codingpit.muviss.feature.search.ui.generated.resources.person_bio_more
import com.codingpit.muviss.feature.search.ui.generated.resources.person_born
import com.codingpit.muviss.feature.search.ui.generated.resources.person_born_in
import com.codingpit.muviss.feature.search.ui.generated.resources.person_died
import com.codingpit.muviss.feature.search.ui.generated.resources.person_in_library
import com.codingpit.muviss.feature.search.ui.generated.resources.person_known_for
import com.codingpit.muviss.feature.search.ui.generated.resources.person_no_credits
import com.codingpit.muviss.feature.search.ui.generated.resources.person_tab_acting
import com.codingpit.muviss.feature.search.ui.generated.resources.person_upcoming
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.PersonDetails
import org.jetbrains.compose.resources.stringResource

internal const val PERSON_LIST_TAG = "personList"
internal const val PERSON_BIO_TAG = "personBio"
internal const val PERSON_KNOWN_FOR_TAG = "personKnownFor"

/** Lines of biography shown before "Read more". */
private const val BIO_COLLAPSED_LINES = 6

/**
 * Who someone is and what they have worked on. Every title opens its Detail, so this is a way to
 * follow a person from a title you like to the next one.
 */
@Composable
fun PersonScreen(
    viewModel: PersonViewModel,
    onBack: () -> Unit,
    onOpenDetail: (MediaId) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        val person = state.person
        val filmography = state.filmography
        when {
            state.loading && person == null -> CircularProgressIndicator(Modifier.align(Alignment.Center).padding(MuvissSpacing.xl))
            state.error != null && person == null -> ErrorState(message = state.error!!, onRetry = viewModel::load, modifier = Modifier.align(Alignment.Center))
            person != null && filmography != null -> PersonBody(person, filmography, state.libraryIds, onOpenDetail)
        }
        BackButton(onBack, Modifier.statusBarsPadding().padding(12.dp))
    }
}

@Composable
internal fun PersonBody(
    person: PersonDetails,
    filmography: Filmography,
    libraryIds: Set<MediaId>,
    onOpenDetail: (MediaId) -> Unit,
) {
    var onActingTab by rememberSaveable(person.id.toString()) { mutableStateOf(filmography.startsOnActing) }
    val tab = if (onActingTab) filmography.acting else filmography.crew
    val inset = Modifier.padding(horizontal = MuvissSpacing.l)

    LazyColumn(
        Modifier.fillMaxSize().testTag(PERSON_LIST_TAG),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = MuvissSpacing.xxl, bottom = MuvissSpacing.bottomContent),
        verticalArrangement = Arrangement.spacedBy(MuvissSpacing.s),
    ) {
        item(key = "header", contentType = "header") { PersonHeader(person, inset) }
        person.biography?.let { bio -> item(key = "bio", contentType = "bio") { Biography(bio, inset) } }

        if (filmography.knownFor.isNotEmpty()) {
            item(key = "knownFor", contentType = "knownFor") { KnownFor(filmography.knownFor, libraryIds, onOpenDetail, inset) }
        }

        if (filmography.acting.isEmpty() && filmography.crew.isEmpty()) {
            item(key = "none", contentType = "none") {
                Text(stringResource(Res.string.person_no_credits), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = inset)
            }
        }
        if (filmography.hasBothTabs) {
            item(key = "tabs", contentType = "tabs") {
                Row(inset.padding(top = MuvissSpacing.s), horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.s)) {
                    FilterChip(selected = onActingTab, onClick = { onActingTab = true }, label = { Text(stringResource(Res.string.person_tab_acting)) })
                    FilterChip(selected = !onActingTab, onClick = { onActingTab = false }, label = { Text(stringResource(Res.string.crew)) })
                }
            }
        }
        items(tab, key = { (if (onActingTab) "a-" else "c-") + it.title.id }, contentType = { "credit" }) { entry ->
            CreditEntryRow(entry, inLibrary = entry.title.id in libraryIds, onClick = { onOpenDetail(entry.title.id) }, modifier = inset)
        }
    }
}

@Composable
private fun PersonHeader(person: PersonDetails, modifier: Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(MuvissSpacing.xs)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            PosterImage(url = person.profileUrl, title = person.name, modifier = Modifier.size(128.dp).clip(CircleShape), size = PosterSize.Thumbnail)
        }
        Text(person.name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        val born = yearOf(person.birthday)
        val bornLine = when {
            born != null && person.placeOfBirth != null -> stringResource(Res.string.person_born_in, born, person.placeOfBirth!!)
            born != null -> stringResource(Res.string.person_born, born)
            else -> null
        }
        bornLine?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
        yearOf(person.deathday)?.let {
            Text(stringResource(Res.string.person_died, it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "1974-11-11" to "1974". The source's date is ISO; only the year is shown, which needs no locale. */
internal fun yearOf(date: String?): String? = date?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }

@Composable
private fun Biography(text: String, modifier: Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var overflows by rememberSaveable { mutableStateOf(false) }
    Column(modifier.testTag(PERSON_BIO_TAG)) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else BIO_COLLAPSED_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
        )
        if (overflows || expanded) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(if (expanded) Res.string.person_bio_less else Res.string.person_bio_more))
            }
        }
    }
}

@Composable
private fun KnownFor(entries: List<FilmographyEntry>, libraryIds: Set<MediaId>, onOpenDetail: (MediaId) -> Unit, modifier: Modifier) {
    Column(modifier.testTag(PERSON_KNOWN_FOR_TAG), verticalArrangement = Arrangement.spacedBy(MuvissSpacing.s)) {
        CarouselHeader(stringResource(Res.string.person_known_for))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.m)) {
            items(entries, key = { it.title.id.toString() }) { entry ->
                // Same badge as a credit row: the card has no slot for it, so it sits under the card, like the row's line.
                Column(Modifier.width(110.dp)) {
                    PosterCard(title = entry.title.title, posterUrl = entry.title.posterUrl, onClick = { onOpenDetail(entry.title.id) }, modifier = Modifier.fillMaxWidth())
                    if (entry.title.id in libraryIds) {
                        Text(stringResource(Res.string.person_in_library), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
internal fun CreditEntryRow(entry: FilmographyEntry, inLibrary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = MuvissSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            PosterImage(url = entry.title.posterUrl, title = entry.title.title, modifier = Modifier.size(width = 48.dp, height = 72.dp), size = PosterSize.Thumbnail)
        }
        Column(Modifier.weight(1f)) {
            Text(entry.title.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(entry.title.year?.toString(), entry.roles.joinToString(", ").ifEmpty { null }).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (entry.upcoming) {
                Text(stringResource(Res.string.person_upcoming), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
            }
            if (inLibrary) {
                Text(stringResource(Res.string.person_in_library), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
