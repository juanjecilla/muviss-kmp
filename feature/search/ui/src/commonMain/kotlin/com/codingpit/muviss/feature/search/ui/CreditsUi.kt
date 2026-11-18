package com.codingpit.muviss.feature.search.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.designsystem.component.CarouselHeader
import com.codingpit.muviss.core.designsystem.component.PosterImage
import com.codingpit.muviss.core.designsystem.component.PosterSize
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import com.codingpit.muviss.feature.search.ui.generated.resources.Res
import com.codingpit.muviss.feature.search.ui.generated.resources.cast
import com.codingpit.muviss.feature.search.ui.generated.resources.created_by
import com.codingpit.muviss.feature.search.ui.generated.resources.credit_episodes
import com.codingpit.muviss.feature.search.ui.generated.resources.credit_role_episodes
import com.codingpit.muviss.feature.search.ui.generated.resources.credits_title
import com.codingpit.muviss.feature.search.ui.generated.resources.crew
import com.codingpit.muviss.feature.search.ui.generated.resources.department_art
import com.codingpit.muviss.feature.search.ui.generated.resources.department_camera
import com.codingpit.muviss.feature.search.ui.generated.resources.department_costume
import com.codingpit.muviss.feature.search.ui.generated.resources.department_creator
import com.codingpit.muviss.feature.search.ui.generated.resources.department_crew
import com.codingpit.muviss.feature.search.ui.generated.resources.department_directing
import com.codingpit.muviss.feature.search.ui.generated.resources.department_editing
import com.codingpit.muviss.feature.search.ui.generated.resources.department_lighting
import com.codingpit.muviss.feature.search.ui.generated.resources.department_other
import com.codingpit.muviss.feature.search.ui.generated.resources.department_production
import com.codingpit.muviss.feature.search.ui.generated.resources.department_sound
import com.codingpit.muviss.feature.search.ui.generated.resources.department_visual_effects
import com.codingpit.muviss.feature.search.ui.generated.resources.department_writing
import com.codingpit.muviss.feature.search.ui.generated.resources.directed_by
import com.codingpit.muviss.models.Credit
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.PersonId
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** The cast carousel on Detail, for layout checks and tests. */
internal const val DETAIL_CAST_TAG = "detailCast"

/** The "Created by" / "Directed by" line on Detail. */
internal const val DETAIL_KEY_CREW_TAG = "detailKeyCrew"

/** The full credits sheet opened by "See all". */
internal const val CREDITS_SHEET_TAG = "creditsSheet"

/** How many cast cards Detail shows before "See all": the rest would be a long scroll nobody finishes. */
internal const val CAST_PREVIEW_SIZE = 20

/** Whether Detail has anything to say about who made and who is in this title. */
internal fun MediaDetails.hasCredits(): Boolean = cast.isNotEmpty() || crew.isNotEmpty()

/**
 * The one line of crew Detail names: a movie's directors, a show's creators.
 * Everyone else is behind "See all".
 */
internal fun MediaDetails.keyCrew(): List<Credit> {
    val job = if (type == MediaType.MOVIE) DIRECTOR_JOB else Credit.CREATOR_JOB
    return crew.filter { it.job == job }.distinctBy { it.personId }
}

/** TMDB's name for a movie director's job. It is not localized by the source, so it is matched, never shown. */
private const val DIRECTOR_JOB = "Director"

/** What the credits on Detail can do: open the full list, and open one person (null until there is somewhere to go). */
internal class CreditsCallbacks(
    val onSeeAll: () -> Unit,
    val onOpenPerson: ((PersonId) -> Unit)?,
)

/**
 * Cast carousel and key-crew line. Draws nothing for a title the source lists
 * nobody for, so a title with no credits looks exactly as it did before.
 */
@Composable
internal fun CreditsSection(
    details: MediaDetails,
    callbacks: CreditsCallbacks,
    modifier: Modifier = Modifier,
) {
    if (!details.hasCredits()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(MuvissSpacing.s)) {
        KeyCrewLine(details)
        if (details.cast.isNotEmpty()) {
            Column(Modifier.testTag(DETAIL_CAST_TAG), verticalArrangement = Arrangement.spacedBy(MuvissSpacing.s)) {
                CarouselHeader(stringResource(Res.string.cast), onSeeAll = callbacks.onSeeAll)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.m)) {
                    itemsIndexed(details.cast.take(CAST_PREVIEW_SIZE), key = { index, credit -> "${credit.personId}#$index" }) { _, credit ->
                        PersonCard(
                            name = credit.name,
                            profileUrl = credit.profileUrl,
                            subtitle = roleText(credit.character, credit.episodeCount),
                            onClick = callbacks.onOpenPerson?.let { open -> { open(credit.personId) } },
                        )
                    }
                }
            }
        } else {
            // Crew but no cast: "See all" is still how the rest of the crew is reached.
            CarouselHeader(stringResource(Res.string.credits_title), onSeeAll = callbacks.onSeeAll)
        }
    }
}

@Composable
private fun KeyCrewLine(details: MediaDetails) {
    val people = details.keyCrew()
    if (people.isEmpty()) return
    val names = people.joinToString(", ") { it.name }
    Text(
        stringResource(if (details.type == MediaType.MOVIE) Res.string.directed_by else Res.string.created_by, names),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(DETAIL_KEY_CREW_TAG),
    )
}

/**
 * A person's round photo with a name and a line under it. The same card shows
 * on Detail's cast and on an episode's guest stars and crew, so the two look
 * and tap alike. Not clickable when [onClick] is null.
 */
@Composable
internal fun PersonCard(
    name: String,
    profileUrl: String?,
    subtitle: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val clickable = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Column(modifier.width(80.dp).then(clickable), horizontalAlignment = Alignment.CenterHorizontally) {
        Avatar(name, profileUrl, 64)
        Text(
            name,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = MuvissSpacing.xs),
        )
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Avatar(name: String, profileUrl: String?, sizeDp: Int) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        if (profileUrl != null) {
            PosterImage(
                url = profileUrl,
                title = name,
                modifier = Modifier.size(sizeDp.dp).clip(CircleShape),
                size = PosterSize.Thumbnail,
            )
        } else {
            Box(Modifier.size(sizeDp.dp), contentAlignment = Alignment.Center) {
                Icon(MuvissIcons.Profile, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** "Character · 12 eps", or whichever half is known; null when neither is. */
@Composable
internal fun roleText(role: String?, episodes: Int?): String? = when {
    role != null && episodes != null -> pluralStringResource(Res.plurals.credit_role_episodes, episodes, role, episodes)
    role != null -> role
    episodes != null -> pluralStringResource(Res.plurals.credit_episodes, episodes, episodes)
    else -> null
}

/**
 * Everyone credited on a title: the whole cast, then the crew by department.
 * It reads the [details] Detail already holds, so opening it asks the source
 * for nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CreditsSheet(
    details: MediaDetails,
    onDismiss: () -> Unit,
    onOpenPerson: ((PersonId) -> Unit)?,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag(CREDITS_SHEET_TAG),
    ) {
        val departments = details.crew.groupBy { it.department.orEmpty() }.entries.sortedWith(compareBy(DEPARTMENT_ORDER) { it.key })
        LazyColumn(
            Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = MuvissSpacing.l, vertical = MuvissSpacing.s),
        ) {
            if (details.cast.isNotEmpty()) {
                item(key = "cast-header", contentType = "header") { SheetHeader(stringResource(Res.string.cast)) }
                itemsIndexed(details.cast, key = { index, _ -> "cast-$index" }, contentType = { _, _ -> "credit" }) { _, credit ->
                    CreditListRow(credit, roleText(credit.character, credit.episodeCount), onOpenPerson)
                }
            }
            if (departments.isNotEmpty()) {
                item(key = "crew-header", contentType = "header") { SheetHeader(stringResource(Res.string.crew)) }
            }
            departments.forEach { (department, credits) ->
                item(key = "dept-$department", contentType = "department") {
                    Text(
                        departmentLabel(department),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = MuvissSpacing.m, bottom = MuvissSpacing.xs).semantics { heading() },
                    )
                }
                itemsIndexed(credits, key = { index, _ -> "dept-$department-$index" }, contentType = { _, _ -> "credit" }) { _, credit ->
                    CreditListRow(credit, roleText(credit.job, credit.episodeCount), onOpenPerson)
                }
            }
        }
    }
}

@Composable
private fun SheetHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(vertical = MuvissSpacing.s).semantics { heading() },
    )
}

@Composable
private fun CreditListRow(credit: Credit, subtitle: String?, onOpenPerson: ((PersonId) -> Unit)?) {
    val clickable = if (onOpenPerson != null) Modifier.clickable(role = Role.Button) { onOpenPerson(credit.personId) } else Modifier
    Row(
        Modifier.fillMaxWidth().then(clickable).padding(vertical = MuvissSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.m),
    ) {
        Avatar(credit.name, credit.profileUrl, 44)
        Column(Modifier.weight(1f)) {
            Text(credit.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** TMDB's department names, in the order a crew list reads best; any other department follows alphabetically. */
private val DEPARTMENT_RANK = listOf("Creator", "Directing", "Writing", "Production", "Camera", "Editing", "Sound", "Art", "Costume & Make-Up", "Visual Effects", "Lighting", "Crew")

private val DEPARTMENT_ORDER = compareBy<String>({ DEPARTMENT_RANK.indexOf(it).let { rank -> if (rank < 0) Int.MAX_VALUE else rank } }, { it })

/** The department in the app's language. A name this app has no string for is shown as the source gave it. */
@Composable
internal fun departmentLabel(department: String): String = when (department) {
    "" -> stringResource(Res.string.department_other)
    "Creator" -> stringResource(Res.string.department_creator)
    "Directing" -> stringResource(Res.string.department_directing)
    "Writing" -> stringResource(Res.string.department_writing)
    "Production" -> stringResource(Res.string.department_production)
    "Camera" -> stringResource(Res.string.department_camera)
    "Editing" -> stringResource(Res.string.department_editing)
    "Sound" -> stringResource(Res.string.department_sound)
    "Art" -> stringResource(Res.string.department_art)
    "Costume & Make-Up" -> stringResource(Res.string.department_costume)
    "Visual Effects" -> stringResource(Res.string.department_visual_effects)
    "Lighting" -> stringResource(Res.string.department_lighting)
    "Crew" -> stringResource(Res.string.department_crew)
    else -> department
}
