package com.codingpit.muviss.feature.triage.domain

/**
 * Where one deck stood on this device (#722). Per device, like a Device Switch: it is never synced, exported
 * or restored, and Delete all data wipes it. The stored pages are what a fresh process resumes from.
 *
 * [moviesResumePage] and [tvResumePage] are the earliest page still holding a card the person has not acted
 * on, or the next page to read when none does. A batch can span five pages, so checkpointing only the next
 * page would skip cards from the first pages of a batch the person never reached before the process died.
 */
data class SavedDeckCursor(
    val moviePage: Int,
    val tvPage: Int,
    val moviesResumePage: Int,
    val tvResumePage: Int,
    val savedAtEpochMs: Long,
)

/** Keeps one [SavedDeckCursor] per deck key. The adapter is the only thing that knows the storage. */
interface DeckCursorStore {
    suspend fun read(key: String): SavedDeckCursor?
    suspend fun write(key: String, saved: SavedDeckCursor)
}

/** The store of a use case built without one, as the tests and previews do. Nothing is remembered. */
object NoDeckCursorStore : DeckCursorStore {
    override suspend fun read(key: String): SavedDeckCursor? = null
    override suspend fun write(key: String, saved: SavedDeckCursor) = Unit
}

/** The TMDB language the deck's titles are read in, part of the key a cursor is saved under. */
fun interface DeckLanguage {
    fun current(): String
}

/** Where a deck position is saved and under which language, as one seam for [LoadDeckUseCase]. */
class DeckResumption(val cursors: DeckCursorStore, val language: DeckLanguage) {
    companion object {
        /** Nothing is remembered: the use case of a test or a preview. */
        val NONE = DeckResumption(NoDeckCursorStore, DeckLanguage { "" })
    }
}

/**
 * The resume rules (#722). Popularity on TMDB is built from daily counts and has no published recompute
 * schedule, so a page number from yesterday points at different titles today. A saved position is therefore
 * good for [TTL_MS] only, and a restore rewinds [REWIND_PAGES] pages to absorb small drift. Re-reading is
 * cheap and safe, because decided titles are excluded on the way in.
 */
object DeckResume {
    const val TTL_MS: Long = 24L * 60 * 60 * 1000
    const val REWIND_PAGES: Int = 2

    /** The filter and language a position belongs to: two filters have two positions. */
    fun key(filter: DeckFilter, language: String): String {
        val muted = filter.muted.keys.map { "${it.type.wireName}:${it.id}" }.sorted().joinToString(",")
        return listOf(filter.type?.wireName ?: "any", filter.genreId ?: "-", muted, language).joinToString("|")
    }

    /**
     * The cursor a fresh process starts from. A position older than [TTL_MS], or one stamped in the future
     * (a clock set back), is ignored and the deck starts at page 1.
     */
    fun restore(saved: SavedDeckCursor?, nowEpochMs: Long): DeckCursor {
        if (saved == null) return DeckCursor()
        val age = nowEpochMs - saved.savedAtEpochMs
        if (age !in 0..TTL_MS) return DeckCursor()
        return DeckCursor(moviePage = rewound(saved.moviesResumePage), tvPage = rewound(saved.tvResumePage))
    }

    /**
     * The position to save: the cursor's next pages, and for each type the earliest page the caller still has
     * unacted cards from ([unactedMovie] / [unactedTv], null when none).
     */
    fun checkpoint(cursor: DeckCursor, unactedMovie: Int?, unactedTv: Int?, nowEpochMs: Long): SavedDeckCursor = SavedDeckCursor(
        moviePage = cursor.moviePage,
        tvPage = cursor.tvPage,
        moviesResumePage = minOf(unactedMovie ?: cursor.moviePage, cursor.moviePage),
        tvResumePage = minOf(unactedTv ?: cursor.tvPage, cursor.tvPage),
        savedAtEpochMs = nowEpochMs,
    )

    private fun rewound(resumePage: Int): Int = (resumePage - REWIND_PAGES).coerceAtLeast(1)
}
