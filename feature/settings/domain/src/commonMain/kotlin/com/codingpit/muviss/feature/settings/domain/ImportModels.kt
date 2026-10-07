package com.codingpit.muviss.feature.settings.domain

import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.MetadataError

/** Which importer produced an [ImportPayload] — drives format auto-detect and the preview screen's label. */
enum class ImportSource {
    TRAKT,
    TV_TIME,
    GENERIC_CSV,
}

/**
 * The external ids a source's export carries for a title, used to resolve a
 * [MediaId] via [ExternalIdResolver]. Both are optional and independent: a
 * row with only a TMDB id skips the network round-trip entirely (it maps
 * directly), one with only an IMDb id goes through TMDB's `/find` endpoint,
 * and one with neither is unresolvable and reported, never guessed at by title.
 */
data class ExternalTitleRef(
    val imdbId: String? = null,
    val tmdbId: String? = null,
) {
    val isEmpty: Boolean get() = imdbId == null && tmdbId == null
}

/** One episode a source reports as watched. Presence in this list always means "seen" — parsers never emit an unwatched episode row. */
data class ImportedEpisode(
    val seasonNumber: Int,
    val episodeNumber: Int,
)

/**
 * One title parsed out of an import file, before id resolution. A TV title's
 * watched episodes accumulate in [episodes] (parsers group same-show rows
 * together, see `ImportTitleAccumulator`); a movie's seen flag is [watched].
 * [rating] is 1-10, already normalized by the parser that produced it.
 */
data class ImportedTitle(
    val externalRef: ExternalTitleRef,
    val type: MediaType?,
    val displayTitle: String,
    val rating: Int? = null,
    val watched: Boolean = false,
    val episodes: List<ImportedEpisode> = emptyList(),
)

/**
 * The result of parsing one import file: every title it describes, plus how
 * many rows the parser couldn't interpret at all (missing required columns,
 * unparseable JSON entries, …) — surfaced in the preview so a partially
 * malformed file doesn't fail silently.
 */
data class ImportPayload(
    val source: ImportSource,
    val titles: List<ImportedTitle>,
    val skippedRowCount: Int = 0,
)

/** Parses one supported export format's raw file content into a source-agnostic [ImportPayload]. Pure — no I/O, no suspension. */
interface ImportParser {
    val source: ImportSource

    fun parse(content: String): ImportPayload
}

/** A title whose [ExternalTitleRef] resolved to a real [MediaId] on TMDB. */
data class ResolvedImportTitle(
    val title: ImportedTitle,
    val mediaId: MediaId,
)

/** A title [ExternalIdResolver] could not place — reported, never dropped (EPIC 18's acceptance criterion). */
data class UnresolvedImportTitle(
    val title: ImportedTitle,
    val reason: UnresolvedReason,
)

/**
 * Why a title could not be placed on TMDB. A reason, not copy: the UI words
 * it in the user's language (#219).
 */
enum class UnresolvedReason {
    /** The source row carried neither an IMDb nor a TMDB id. */
    NoExternalId,

    /** The source did not say whether the title is a movie or a show. */
    UnknownMediaType,

    /** The ids were there, but TMDB has nothing for them (or the lookup failed). */
    NoMatch,
}

/**
 * The preview shown before the user confirms an import: every title split
 * into resolved (will be imported) and unresolved (will be reported, not
 * imported), plus the parser's own skip count. [resolved] is kept around so
 * [ApplyImportUseCase] doesn't have to resolve ids a second time.
 */
data class ImportPreview(
    val source: ImportSource,
    val resolved: List<ResolvedImportTitle>,
    val unresolved: List<UnresolvedImportTitle>,
    val skippedRowCount: Int,
) {
    val titleCount: Int get() = resolved.size + unresolved.size
    val episodeCount: Int get() = (resolved.asSequence().map { it.title } + unresolved.asSequence().map { it.title })
        .sumOf { it.episodes.size }
    val unresolvedCount: Int get() = unresolved.size
}

/** One resolved title whose collection/progress write failed at apply time (e.g. the details fetch errored). */
data class FailedImportTitle(
    val title: ResolvedImportTitle,
    val error: MetadataError,
)

/** The result summary shown after an import runs: what landed, what didn't, and why. */
data class ImportApplyResult(
    val importedTitleCount: Int,
    val episodeTickCount: Int,
    val unresolved: List<UnresolvedImportTitle>,
    val failed: List<FailedImportTitle> = emptyList(),
)
