package com.codingpit.muviss.feature.search.domain

import com.codingpit.muviss.models.MediaAnchors
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.PersonAnchors
import com.codingpit.muviss.models.PersonId
import com.codingpit.muviss.models.SourceId

enum class ExternalLinkKind {
    IMDB,
    WIKIPEDIA,

    /** The English edition, offered beside [WIKIPEDIA] when the app language is not English (#990). */
    WIKIPEDIA_ENGLISH,
    TMDB,
}

/** A page about a title or a person on a site outside Muviss. Opened in the system browser; nothing is sent. */
data class ExternalLink(val kind: ExternalLinkKind, val url: String)

/**
 * Where to read more about a title or a person. Built from ids already fetched: no request is made
 * to find them, so a link whose id is missing, or is not shaped like the site's ids, is left out
 * rather than guessed. The shape check is also what keeps an odd string from the source out of a URL.
 *
 * Wikipedia goes through Wikidata's `Special:GoToLinkedPage`, which redirects to the article in the
 * given language. When that language has no article Wikidata shows a "no linked page" screen
 * instead (checked by hand: a 200 that stays on the Special page). Finding out first would take a request to a
 * host Muviss does not otherwise talk to, so when the app language is not English an English edition of the same
 * item is offered beside it (#990). A title with no Wikidata id has no Wikipedia link at all.
 */
object ExternalLinks {
    private val IMDB_TITLE = Regex("tt\\d+")
    private val IMDB_NAME = Regex("nm\\d+")
    private val WIKIDATA_ITEM = Regex("Q\\d+")
    private val NUMERIC = Regex("\\d+")
    private val LANGUAGE = Regex("[a-z]{2,3}")

    fun forTitle(id: MediaId, anchors: MediaAnchors, language: String): List<ExternalLink> = buildList {
        imdb(anchors.imdbId, IMDB_TITLE, "title")?.let { add(it) }
        addAll(wikipedia(anchors.wikidataId, language))
        tmdb(id.source, id.external, if (id.type == MediaType.MOVIE) "movie" else "tv")?.let { add(it) }
    }

    fun forPerson(id: PersonId, anchors: PersonAnchors, language: String): List<ExternalLink> = buildList {
        imdb(anchors.imdbId, IMDB_NAME, "name")?.let { add(it) }
        addAll(wikipedia(anchors.wikidataId, language))
        tmdb(id.source, id.external, "person")?.let { add(it) }
    }

    private fun imdb(id: String?, shape: Regex, path: String): ExternalLink? = id?.takeIf { shape.matches(it) }?.let { ExternalLink(ExternalLinkKind.IMDB, "https://www.imdb.com/$path/$it/") }

    private fun wikipedia(wikidataId: String?, language: String): List<ExternalLink> {
        val item = wikidataId?.takeIf { WIKIDATA_ITEM.matches(it) } ?: return emptyList()
        // An unrecognised language is English rather than no link: English has an article for nearly everything.
        val lang = language.substringBefore('-').lowercase().takeIf { LANGUAGE.matches(it) } ?: "en"
        val selected = ExternalLink(ExternalLinkKind.WIKIPEDIA, edition(lang, item))
        if (lang == "en") return listOf(selected)
        return listOf(selected, ExternalLink(ExternalLinkKind.WIKIPEDIA_ENGLISH, edition("en", item)))
    }

    private fun edition(lang: String, item: String) = "https://www.wikidata.org/wiki/Special:GoToLinkedPage/${lang}wiki/$item"

    private fun tmdb(source: SourceId, id: String, path: String): ExternalLink? = id.takeIf { source == SourceId.TMDB && NUMERIC.matches(it) }?.let { ExternalLink(ExternalLinkKind.TMDB, "https://www.themoviedb.org/$path/$it") }
}
