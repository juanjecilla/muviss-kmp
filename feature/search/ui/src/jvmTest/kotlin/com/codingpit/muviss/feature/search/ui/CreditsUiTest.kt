@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.models.Credit
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.PersonId
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CreditsUiTest {

    private val originalLocale: Locale = Locale.getDefault()

    @BeforeTest
    fun pinEnglish() = Locale.setDefault(Locale.forLanguageTag("en-US"))

    @AfterTest
    fun restoreLocale() = Locale.setDefault(originalLocale)

    private fun credit(id: String, name: String, character: String? = null, job: String? = null, department: String? = null) = Credit(PersonId.tmdb(id), name, character = character, job = job, department = department)

    private val movie = MediaDetails(
        summary = MediaSummary(MediaId.tmdbMovie("1"), "Movie"),
        cast = listOf(credit("1", "Lead Actor", character = "The Hero"), credit("2", "Second Actor", character = "The Villain")),
        crew = listOf(
            credit("9", "Some Director", job = "Director", department = "Directing"),
            credit("10", "Some Writer", job = "Screenplay", department = "Writing"),
        ),
    )

    private val show = MediaDetails(
        summary = MediaSummary(MediaId.tmdbTv("1"), "Show"),
        cast = listOf(credit("1", "Lead Actor", character = "The Hero").copy(episodeCount = 70)),
        crew = listOf(credit("7", "Show Creator", job = Credit.CREATOR_JOB, department = Credit.CREATOR_DEPARTMENT)),
    )

    @Test
    fun a_movie_shows_its_cast_and_who_directed_it() = runComposeUiTest {
        setContent { MuvissTheme(darkTheme = false) { CreditsSection(movie, CreditsCallbacks({}, null)) } }

        onNodeWithText("Lead Actor").assertIsDisplayed()
        onNodeWithText("The Hero").assertIsDisplayed()
        onNodeWithText("Directed by Some Director").assertIsDisplayed()
    }

    @Test
    fun a_show_says_who_created_it_and_how_many_episodes_a_cast_member_covers() = runComposeUiTest {
        setContent { MuvissTheme(darkTheme = false) { CreditsSection(show, CreditsCallbacks({}, null)) } }

        onNodeWithText("Created by Show Creator").assertIsDisplayed()
        onNodeWithText("The Hero · 70 eps").assertIsDisplayed()
    }

    @Test
    fun a_title_with_no_credits_draws_nothing() = runComposeUiTest {
        val bare = MediaDetails(MediaSummary(MediaId.tmdbMovie("2"), "Bare"))
        setContent { MuvissTheme(darkTheme = false) { CreditsSection(bare, CreditsCallbacks({}, null)) } }

        onAllNodesWithTag(DETAIL_CAST_TAG).assertCountEquals(0)
        onAllNodesWithTag(DETAIL_KEY_CREW_TAG).assertCountEquals(0)
    }

    @Test
    fun see_all_asks_to_open_the_full_credits() = runComposeUiTest {
        var opened = 0
        setContent { MuvissTheme(darkTheme = false) { CreditsSection(movie, CreditsCallbacks({ opened++ }, null)) } }

        onNodeWithText("See all ›").performClick()

        assertEquals(1, opened)
    }

    @Test
    fun a_card_opens_its_person_when_a_destination_exists() = runComposeUiTest {
        val opened = mutableListOf<PersonId>()
        setContent { MuvissTheme(darkTheme = false) { CreditsSection(movie, CreditsCallbacks({}, { opened += it })) } }

        onNodeWithText("Lead Actor").performClick()

        assertEquals(listOf(PersonId.tmdb("1")), opened)
    }

    @Test
    fun the_full_credits_list_the_cast_then_the_crew_under_their_departments() = runComposeUiTest {
        setContent { MuvissTheme(darkTheme = false) { CreditsSheet(movie, onDismiss = {}, onOpenPerson = null) } }

        onNodeWithText("Second Actor").assertIsDisplayed()
        onNodeWithText("Directing").assertIsDisplayed()
        onNodeWithText("Some Director").assertIsDisplayed()
        onNodeWithText("Writing").assertIsDisplayed()
        onNodeWithText("Screenplay").assertIsDisplayed()
    }

    @Test
    fun the_full_credits_reach_past_the_detail_preview() = runComposeUiTest {
        // Detail shows CAST_PREVIEW_SIZE cards; the sheet is the only way to reach the rest.
        val longCast = MediaDetails(
            summary = MediaSummary(MediaId.tmdbMovie("2"), "Ensemble"),
            cast = (1..CAST_PREVIEW_SIZE + 5).map { credit("$it", "Person $it", character = "Role $it") },
        )
        setContent { MuvissTheme(darkTheme = false) { CreditsSheet(longCast, onDismiss = {}, onOpenPerson = null) } }

        onNode(hasScrollToKeyAction()).performScrollToKey("cast-${CAST_PREVIEW_SIZE + 4}")
        onNodeWithText("Person ${CAST_PREVIEW_SIZE + 5}").assertIsDisplayed()
    }

    @Test
    fun the_full_credits_name_a_crew_job_in_the_app_language() = withSpanish {
        val crew = MediaDetails(
            summary = MediaSummary(MediaId.tmdbMovie("3"), "Scored"),
            crew = listOf(
                credit("20", "Score Person", job = "Original Music Composer", department = "Sound"),
                credit("21", "Cam Person", job = "Director of Photography", department = "Camera"),
            ),
        )
        runComposeUiTest {
            setContent { MuvissTheme(darkTheme = false) { CreditsSheet(crew, onDismiss = {}, onOpenPerson = null) } }

            onNodeWithText("Compositor de la música original").assertIsDisplayed()
            onNodeWithText("Director de fotografía").assertIsDisplayed()
            onNodeWithText("Original Music Composer").assertDoesNotExist()
        }
    }

    @Test
    fun a_crew_job_the_app_has_no_name_for_is_shown_as_the_source_gave_it() = withSpanish {
        val crew = MediaDetails(
            summary = MediaSummary(MediaId.tmdbMovie("4"), "Unmapped"),
            crew = listOf(credit("22", "Rig Person", job = "Chaperone", department = "Crew")),
        )
        runComposeUiTest {
            setContent { MuvissTheme(darkTheme = false) { CreditsSheet(crew, onDismiss = {}, onOpenPerson = null) } }

            onNodeWithText("Chaperone").assertIsDisplayed()
        }
    }

    @Test
    fun the_full_credits_keep_english_job_names_in_english() = withEnglish {
        runComposeUiTest {
            setContent { MuvissTheme(darkTheme = false) { CreditsSheet(movie, onDismiss = {}, onOpenPerson = null) } }

            onNodeWithText("Director").assertIsDisplayed()
            onNodeWithText("Screenplay").assertIsDisplayed()
        }
    }

    private fun withSpanish(block: () -> Unit) = withLocaleTag("es-ES", block)

    private fun withEnglish(block: () -> Unit) = withLocaleTag("en-US", block)

    private fun withLocaleTag(tag: String, block: () -> Unit) {
        Locale.setDefault(Locale.forLanguageTag(tag))
        block()
    }
}
