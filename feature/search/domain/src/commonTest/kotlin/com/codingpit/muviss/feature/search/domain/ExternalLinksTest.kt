package com.codingpit.muviss.feature.search.domain

import com.codingpit.muviss.models.MediaAnchors
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.PersonAnchors
import com.codingpit.muviss.models.PersonId
import com.codingpit.muviss.models.SourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExternalLinksTest {

    private fun urls(links: List<ExternalLink>) = links.map { it.kind to it.url }

    @Test
    fun a_movie_links_to_imdb_wikipedia_and_tmdb() {
        val links = ExternalLinks.forTitle(MediaId.tmdbMovie("27205"), MediaAnchors("tt1375666", "Q25188"), "en-US")

        assertEquals(
            listOf(
                ExternalLinkKind.IMDB to "https://www.imdb.com/title/tt1375666/",
                ExternalLinkKind.WIKIPEDIA to "https://www.wikidata.org/wiki/Special:GoToLinkedPage/enwiki/Q25188",
                ExternalLinkKind.TMDB to "https://www.themoviedb.org/movie/27205",
            ),
            urls(links),
        )
    }

    @Test
    fun a_show_uses_the_tv_path_on_tmdb() {
        val links = ExternalLinks.forTitle(MediaId.tmdbTv("1399"), MediaAnchors(), "en")

        assertEquals(listOf(ExternalLinkKind.TMDB to "https://www.themoviedb.org/tv/1399"), urls(links))
    }

    @Test
    fun a_person_links_to_their_imdb_name_page() {
        val links = ExternalLinks.forPerson(PersonId.tmdb("6193"), PersonAnchors("nm0000138", "Q38111"), "en")

        assertEquals("https://www.imdb.com/name/nm0000138/", links[0].url)
        assertEquals("https://www.themoviedb.org/person/6193", links[2].url)
    }

    @Test
    fun wikipedia_follows_the_app_language_without_its_region() {
        val links = ExternalLinks.forTitle(MediaId.tmdbMovie("1"), MediaAnchors(wikidataId = "Q1"), "es-ES")

        assertTrue(links.single { it.kind == ExternalLinkKind.WIKIPEDIA }.url.endsWith("/eswiki/Q1"))
    }

    @Test
    fun the_wikipedia_edition_follows_the_app_language_code() {
        // Every language the Settings picker offers, plus the unset sentinel (`SupportedLocales.SYSTEM_DEFAULT_LANGUAGE`, empty).
        val editions = listOf(
            "en-US" to "enwiki",
            "es-ES" to "eswiki",
            "fr-FR" to "frwiki",
            "de-DE" to "dewiki",
            "it-IT" to "itwiki",
            "pt-BR" to "ptwiki",
            "ja-JP" to "jawiki",
            "" to "enwiki",
        )

        for ((language, edition) in editions) {
            val wiki = ExternalLinks.forTitle(MediaId.tmdbMovie("1"), MediaAnchors(wikidataId = "Q1"), language).single { it.kind == ExternalLinkKind.WIKIPEDIA }

            assertTrue(wiki.url.endsWith("/$edition/Q1"), "language '$language' gave ${wiki.url}")
        }
    }

    @Test
    fun an_unrecognised_language_means_english_wikipedia() {
        val links = ExternalLinks.forTitle(MediaId.tmdbMovie("1"), MediaAnchors(wikidataId = "Q1"), "../evil")

        assertTrue(links.single { it.kind == ExternalLinkKind.WIKIPEDIA }.url.endsWith("/enwiki/Q1"))
    }

    @Test
    fun a_link_whose_id_is_missing_is_left_out() {
        val links = ExternalLinks.forTitle(MediaId.tmdbMovie("1"), MediaAnchors(imdbId = null, wikidataId = null), "en")

        assertEquals(listOf(ExternalLinkKind.TMDB), links.map { it.kind })
    }

    @Test
    fun an_id_that_is_not_shaped_like_the_sites_is_never_put_in_a_url() {
        val links = ExternalLinks.forTitle(MediaId.tmdbMovie("1"), MediaAnchors(imdbId = "tt1/../x", wikidataId = "Q1?a=b"), "en")
        val person = ExternalLinks.forPerson(PersonId.tmdb("1"), PersonAnchors(imdbId = "tt0000001", wikidataId = ""), "en")

        assertEquals(listOf(ExternalLinkKind.TMDB), links.map { it.kind })
        // A title id is not a name id: IMDb would 404 it.
        assertEquals(listOf(ExternalLinkKind.TMDB), person.map { it.kind })
    }

    @Test
    fun a_title_from_another_source_has_no_tmdb_link() {
        val other = MediaId(SourceId("tvmaze"), com.codingpit.muviss.models.MediaType.TV, "1")

        assertTrue(ExternalLinks.forTitle(other, MediaAnchors(), "en").isEmpty())
    }
}
