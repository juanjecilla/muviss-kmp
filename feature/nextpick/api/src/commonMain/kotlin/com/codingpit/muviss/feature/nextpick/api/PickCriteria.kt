package com.codingpit.muviss.feature.nextpick.api

import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType

/**
 * What a Pick Session boils down to (EPIC 51, ADR 0032): the filters the pick engine (#399) applies
 * to the backlog and to TMDB. Plain data with no behaviour, and every field defaults to "no
 * constraint" because every question can be skipped: a skipped answer adds nothing.
 *
 * Built by the session in `:feature:nextpick:domain`, used once and not stored.
 */
data class PickCriteria(
    val source: PickSource = PickSource.EITHER,
    /** Null is both movies and shows. */
    val type: MediaType? = null,
    val runtime: PickRuntime? = null,
    /** Moods the person picked; the engine unions their genre sets (see `MoodGenres` in the domain). */
    val moods: Set<PickMood> = emptySet(),
    val anchor: PickAnchor? = null,
    val era: PickEra? = null,
    /** An ISO 639-1 code (`"es"`), or null for any language. */
    val originalLanguage: String? = null,
    val onlyMyServices: Boolean = false,
    val company: PickCompany? = null,
    val commitment: PickCommitment? = null,
    /** Whether a title the person has already watched may come up again. Null is "did not say". */
    val rewatchOk: Boolean? = null,
)

/** Where a pick may come from. */
enum class PickSource {
    /** Only what is already in the person's library, so it works offline. */
    MY_LIST,

    /** Only titles that are not in the library or ruled on. */
    SOMETHING_NEW,

    /** The default, and what skipping the question means. */
    EITHER,
}

/** How long the person has. Movies and shows read it differently (a movie's runtime, a show's episode runtime). */
enum class PickRuntime {
    /** About an hour and a half or less. */
    QUICK,

    /** An evening: up to about two and a half hours. */
    EVENING,

    /** Something to get lost in: a long film or a show with many episodes. */
    BINGE,
}

enum class PickMood { LIGHT, TENSE, THOUGHTFUL, ESCAPE, ROMANTIC }

/** More or less like a title, usually the one just finished. */
data class PickAnchor(val mediaId: MediaId, val like: Boolean)

enum class PickEra { NEW, CLASSIC }

/** Who is watching. [FAMILY] is the only one that narrows the result (a certification filter). */
enum class PickCompany { ALONE, PARTNER, FAMILY }

/** For shows: how much of a commitment the person is up for. */
enum class PickCommitment { LIMITED, ENDED, ONGOING }
