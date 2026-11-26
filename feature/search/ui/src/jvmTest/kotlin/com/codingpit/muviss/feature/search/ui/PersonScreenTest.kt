@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
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
    fun the_wikipedia_link_opens_in_the_app_language_not_the_device_language() {
        // The app language comes from the caller (SearchSection reads MetadataLocale); the row must never read the device locale.
        val cases = listOf("es-ES" to "eswiki", "ja-JP" to "jawiki", "en-US" to "enwiki")
        for ((language, edition) in cases) {
            val opened = mutableListOf<String>()
            runComposeUiTest {
                setContent {
                    CompositionLocalProvider(LocalUriHandler provides recordingUriHandler(opened)) {
                        MuvissTheme(darkTheme = false) {
                            PersonBody(person, FilmographyOrganizer.organize(person, today), emptySet(), {}, language)
                        }
                    }
                }

                onNodeWithText("Wikipedia").performClick()
            }

            assertEquals("https://www.wikidata.org/wiki/Special:GoToLinkedPage/$edition/Q38111", opened.single(), "language $language")
        }
    }

    @Test
    fun the_english_wikipedia_chip_is_offered_beside_a_non_english_app_language_and_opens_the_same_item() {
        // Issue #990: the selected edition may have no article, so the English one sits beside it, same Q-id.
        val cases = listOf("es-ES" to "https://www.wikidata.org/wiki/Special:GoToLinkedPage/enwiki/Q38111", "ja-JP" to "https://www.wikidata.org/wiki/Special:GoToLinkedPage/enwiki/Q38111")
        for ((language, expected) in cases) {
            val opened = mutableListOf<String>()
            runComposeUiTest {
                setContent {
                    CompositionLocalProvider(LocalUriHandler provides recordingUriHandler(opened)) {
                        MuvissTheme(darkTheme = false) {
                            PersonBody(person, FilmographyOrganizer.organize(person, today), emptySet(), {}, language)
                        }
                    }
                }

                onNodeWithText("Wikipedia (English)").performClick()
            }

            assertEquals(expected, opened.single(), "language $language")
        }
    }

    @Test
    fun an_english_app_language_shows_no_second_wikipedia_chip() = runComposeUiTest {
        setContent {
            MuvissTheme(darkTheme = false) {
                PersonBody(person, FilmographyOrganizer.organize(person, today), emptySet(), {}, "en-US")
            }
        }

        onAllNodesWithText("Wikipedia (English)").assertCountEquals(0)
        onNodeWithText("Wikipedia").assertIsDisplayed()
    }

    @Test
    fun a_person_screen_with_no_language_given_opens_the_english_wikipedia() = runComposeUiTest {
        val opened = mutableListOf<String>()
        setContent {
            CompositionLocalProvider(LocalUriHandler provides recordingUriHandler(opened)) {
                MuvissTheme(darkTheme = false) {
                    PersonBody(person, FilmographyOrganizer.organize(person, today), emptySet(), {})
                }
            }
        }

        onNodeWithText("Wikipedia").performClick()

        assertEquals("https://www.wikidata.org/wiki/Special:GoToLinkedPage/enwiki/Q38111", opened.single())
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
    fun an_unreleased_title_is_flagged() = runComposeUiTest {
        show()

        onNodeWithText("Upcoming").assertIsDisplayed()
    }

    @Test
    fun a_credit_row_in_the_library_is_badged() = runComposeUiTest {
        show(libraryIds = setOf(inception.id))

        onNode(hasText("In your library") and hasAnyAncestor(hasTestTag(PERSON_LIST_TAG)) and !hasAnyAncestor(hasTestTag(PERSON_KNOWN_FOR_TAG))).assertIsDisplayed()
    }

    @Test
    fun a_known_for_card_in_the_library_is_badged_like_a_credit_row() = runComposeUiTest {
        show(libraryIds = setOf(inception.id))

        onNode(hasText("In your library") and hasAnyAncestor(hasTestTag(PERSON_KNOWN_FOR_TAG))).assertIsDisplayed()
    }

    @Test
    fun a_known_for_card_outside_the_library_has_no_badge() = runComposeUiTest {
        show(libraryIds = emptySet())

        onAllNodes(hasText("In your library") and hasAnyAncestor(hasTestTag(PERSON_KNOWN_FOR_TAG))).assertCountEquals(0)
    }

    @Test
    fun a_title_opens_its_detail() = runComposeUiTest {
        val opened = mutableListOf<MediaId>()
        show(onOpenDetail = { opened += it })

        onNodeWithText("Next Film").performClick()

        assertEquals(listOf(upcoming.id), opened)
    }
}

private fun recordingUriHandler(opened: MutableList<String>) = object : UriHandler {
    override fun openUri(uri: String) {
        opened += uri
    }
}
