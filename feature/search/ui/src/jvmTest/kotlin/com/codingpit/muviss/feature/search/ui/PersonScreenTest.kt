@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.feature.search.domain.FilmographyOrganizer
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.PersonCredit
import com.codingpit.muviss.models.PersonDetails
import com.codingpit.muviss.models.PersonId
import kotlin.test.Test
import kotlin.test.assertEquals

class PersonScreenTest {

    private val today = 20_000L
    private val inception = MediaSummary(MediaId.tmdbMovie("1"), "Inception", year = 2010)
    private val upcoming = MediaSummary(MediaId.tmdbMovie("2"), "Next Film", year = 2027)
    private val directed = MediaSummary(MediaId.tmdbMovie("3"), "Directed Film", year = 2005)

    private val person = PersonDetails(
        id = PersonId.tmdb("1"),
        name = "Some Person",
        biography = "A short biography.",
        anchors = com.codingpit.muviss.models.PersonAnchors(imdbId = "nm0000138", wikidataId = "Q38111"),
        birthday = "1974-11-11",
        placeOfBirth = "Los Angeles",
        knownForDepartment = "Acting",
        credits = listOf(
            PersonCredit(inception, character = "Cobb", acting = true, releaseEpochDay = 14_000L, voteCount = 100),
            PersonCredit(upcoming, character = "Hero", acting = true, releaseEpochDay = today + 100, voteCount = 0),
            PersonCredit(directed, job = "Director", acting = false, releaseEpochDay = 13_000L, voteCount = 10),
        ),
    )

    private fun androidx.compose.ui.test.ComposeUiTest.show(libraryIds: Set<MediaId> = emptySet(), onOpenDetail: (MediaId) -> Unit = {}) = setContent {
        MuvissTheme(darkTheme = false) {
            PersonBody(person, FilmographyOrganizer.organize(person, today), libraryIds, onOpenDetail)
        }
    }

    @Test
    fun the_header_shows_the_name_and_where_and_when_they_were_born() = runComposeUiTest {
        show()

        // PosterImage draws the name as its no-artwork fallback, so match the heading, not the text.
        onNode(hasText("Some Person") and SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit)).assertIsDisplayed()
        onNodeWithText("Born 1974 in Los Angeles").assertIsDisplayed()
        onNodeWithText("A short biography.").assertIsDisplayed()
    }

    @Test
    fun the_person_links_out_to_imdb_wikipedia_and_tmdb() = runComposeUiTest {
        show()

        onNodeWithText("IMDb").assertIsDisplayed()
        onNodeWithText("Wikipedia").assertIsDisplayed()
        onNodeWithText("TMDB").assertIsDisplayed()
    }

    @Test
    fun it_opens_on_the_main_department_with_the_work_they_are_known_for() = runComposeUiTest {
        show()

        onNodeWithText("Known for").assertIsDisplayed()
        onNodeWithText("Cobb", substring = true).assertIsDisplayed()
        onAllNodesWithText("Directed Film").assertCountEquals(0)
    }

    @Test
    fun the_crew_tab_shows_the_jobs_and_switching_back_shows_the_roles() = runComposeUiTest {
        show()

        onNodeWithText("Crew").performClick()
        onNodeWithText("Directed Film").assertIsDisplayed()
        onNodeWithText("2005 · Director").assertIsDisplayed()

        onNodeWithText("Acting").performClick()
        onNodeWithText("2010 · Cobb").assertIsDisplayed()
    }

    @Test
    fun an_unreleased_title_is_flagged_and_one_in_the_library_says_so() = runComposeUiTest {
        show(libraryIds = setOf(inception.id))

        onNodeWithText("Upcoming").assertIsDisplayed()
        onNodeWithText("In your library").assertIsDisplayed()
    }

    @Test
    fun a_title_opens_its_detail() = runComposeUiTest {
        val opened = mutableListOf<MediaId>()
        show(onOpenDetail = { opened += it })

        onNodeWithText("Next Film").performClick()

        assertEquals(listOf(upcoming.id), opened)
    }
}
