package com.codingpit.muviss.feature.search.domain

import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.PersonCredit
import com.codingpit.muviss.models.PersonDetails

/** One title in a person's [Filmography], with every role they had in it within that tab. */
data class FilmographyEntry(
    val title: MediaSummary,
    /** "Cobb", or "Director, Writer": the distinct characters or jobs, in the order the source gave them. */
    val roles: List<String>,
    val releaseEpochDay: Long?,
    val voteCount: Int,
    /** Not out yet as of the day this was organized. A title with no date is not upcoming, only undated. */
    val upcoming: Boolean,
)

/**
 * A person's credits arranged for reading. [acting] and [crew] are the two tabs; a title the person both
 * acted in and made appears in each, with that tab's own roles.
 */
data class Filmography(
    /** The person's best-known released work, in their main department. */
    val knownFor: List<FilmographyEntry>,
    val acting: List<FilmographyEntry>,
    val crew: List<FilmographyEntry>,
    /** Which tab opens first: the one for the source's main department, else whichever has anything. */
    val startsOnActing: Boolean,
) {
    val hasBothTabs: Boolean get() = acting.isNotEmpty() && crew.isNotEmpty()
}

/** Turns the source's flat list of roles into a [Filmography]. Pure: the day is passed in. */
object FilmographyOrganizer {
    const val KNOWN_FOR_SIZE = 8
    private const val ACTING = "Acting"

    fun organize(person: PersonDetails, todayEpochDay: Long): Filmography {
        val acting = entries(person.credits.filter { it.acting }, todayEpochDay)
        val crew = entries(person.credits.filter { !it.acting }, todayEpochDay)
        val startsOnActing = when {
            acting.isEmpty() -> false
            crew.isEmpty() -> true
            else -> person.knownForDepartment == ACTING
        }
        return Filmography(knownFor = knownFor(if (startsOnActing) acting else crew), acting = acting, crew = crew, startsOnActing = startsOnActing)
    }

    /** One entry per title, upcoming first (soonest first), then released newest first, then undated. */
    private fun entries(credits: List<PersonCredit>, today: Long): List<FilmographyEntry> {
        val merged = credits.groupBy { it.title.id }.values.map { group ->
            val date = group.firstNotNullOfOrNull { it.releaseEpochDay }
            FilmographyEntry(
                title = group.first().title,
                roles = group.mapNotNull { (if (it.acting) it.character else it.job)?.takeIf { role -> role.isNotBlank() } }.distinct(),
                releaseEpochDay = date,
                voteCount = group.maxOf { it.voteCount },
                upcoming = date != null && date > today,
            )
        }
        val (upcoming, rest) = merged.partition { it.upcoming }
        val (released, undated) = rest.partition { it.releaseEpochDay != null }
        return upcoming.sortedBy { it.releaseEpochDay } + released.sortedByDescending { it.releaseEpochDay } + undated
    }

    /** Released titles only: an undated one is not known to be out, so it cannot be "known for" yet. */
    private fun knownFor(tab: List<FilmographyEntry>): List<FilmographyEntry> = tab
        .filter { it.releaseEpochDay != null && !it.upcoming && it.voteCount > 0 }
        .sortedWith(compareByDescending<FilmographyEntry> { it.voteCount }.thenByDescending { it.releaseEpochDay ?: Long.MIN_VALUE })
        .take(KNOWN_FOR_SIZE)
}
