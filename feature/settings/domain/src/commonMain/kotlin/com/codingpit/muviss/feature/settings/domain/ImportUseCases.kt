package com.codingpit.muviss.feature.settings.domain

import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.toUserMessage
import kotlinx.coroutines.flow.first

/** `(done, total)` progress callback shape shared by [PreviewImportUseCase] and [ApplyImportUseCase] — both loop over one row/title per network call. */
typealias ImportProgressListener = suspend (done: Int, total: Int) -> Unit

private val NO_PROGRESS: ImportProgressListener = { _, _ -> }

/**
 * The picked file cannot be imported, for a reason worth telling the person.
 * [message] is written as user-facing copy (unlike an arbitrary exception's,
 * which the settings screens never show), so the view model may display it.
 */
class ImportFileException(message: String) : Exception(message)

/**
 * Parses a picked file (auto-detecting its [ImportSource]) and resolves every
 * title's [MediaId][com.codingpit.muviss.models.MediaId] via
 * [ExternalIdResolver], producing the counts the preview screen shows before
 * the user confirms. [onProgress] fires once per title (resolution is one
 * network round-trip each) so the UI can render "n of m".
 */
class PreviewImportUseCase(
    private val parsers: List<ImportParser>,
    private val resolver: ExternalIdResolver,
) {
    suspend operator fun invoke(content: String, onProgress: ImportProgressListener = NO_PROGRESS): ImportPreview {
        val source = ImportFormatDetector.detect(content)
            ?: throw ImportFileException("Unrecognized import file — not JSON, and its header doesn't match a supported CSV format")
        val parser = parsers.firstOrNull { it.source == source }
            ?: throw ImportFileException("No importer is available for $source files")
        val payload = parser.parse(content)

        val resolved = mutableListOf<ResolvedImportTitle>()
        val unresolved = mutableListOf<UnresolvedImportTitle>()
        payload.titles.forEachIndexed { index, title ->
            val mediaId = runCatching { resolver.resolve(title.externalRef, title.type) }.getOrNull()
            if (mediaId != null) {
                resolved += ResolvedImportTitle(title, mediaId)
            } else {
                unresolved += UnresolvedImportTitle(title, unresolvedReason(title))
            }
            onProgress(index + 1, payload.titles.size)
        }

        return ImportPreview(payload.source, resolved, unresolved, payload.skippedRowCount)
    }

    private fun unresolvedReason(title: ImportedTitle): String = when {
        title.externalRef.isEmpty -> "No IMDb/TMDB id in the source file"
        title.type == null -> "Unknown media type"
        else -> "No match on TMDB"
    }
}

/**
 * Applies a confirmed [ImportPreview]: for every resolved title, fetches its
 * TMDB snapshot, saves it to the collection, ticks whichever episodes/movie
 * flag the source reported watched, and fills in a rating — **only** when
 * the title has no rating yet, so a re-import (or an import after the user
 * already rated something by hand) never overwrites local edits. This, plus
 * every write below going through the same upsert/tick paths the rest of the
 * app uses (already idempotent — see `CollectionRepository.upsertSnapshot`,
 * `ProgressRepository.setSeen`), is what makes re-running an import on the
 * same file safe: existing entries keep their data, only missing pieces
 * (a not-yet-added title, a not-yet-ticked episode, a not-yet-set rating)
 * are added — see `docs/IMPORT.md` "Idempotency".
 *
 * A resolved title whose details fetch fails is reported in [FailedImportTitle]
 * rather than aborting the whole batch — one bad title shouldn't block the
 * rest, the same "best effort per title" contract
 * `RefreshCollectionSnapshotsUseCase` follows.
 */
class ApplyImportUseCase(
    private val detailsSource: ImportMediaDetailsSource,
    private val collectionApi: CollectionApi,
    private val progressApi: ProgressApi,
) {
    suspend operator fun invoke(preview: ImportPreview, onProgress: ImportProgressListener = NO_PROGRESS): ImportApplyResult {
        var importedCount = 0
        var tickCount = 0
        val failed = mutableListOf<FailedImportTitle>()

        preview.resolved.forEachIndexed { index, resolved ->
            detailsSource.fetch(resolved.mediaId).fold(
                onSuccess = { details ->
                    collectionApi.add(details)
                    importedCount++
                    tickCount += applyProgress(resolved, details)
                    applyRating(resolved)
                },
                onFailure = { e -> failed += FailedImportTitle(resolved, e.toUserMessage("Unknown error")) },
            )
            onProgress(index + 1, preview.resolved.size)
        }

        return ImportApplyResult(importedCount, tickCount, preview.unresolved, failed)
    }

    private suspend fun applyProgress(resolved: ResolvedImportTitle, details: MediaDetails): Int = when (details.type) {
        MediaType.MOVIE -> if (resolved.title.watched) {
            progressApi.setMovieWatched(resolved.mediaId, true)
            1
        } else {
            0
        }

        MediaType.TV -> {
            resolved.title.episodes.forEach { ep ->
                progressApi.setEpisodeSeen(EpisodeId(resolved.mediaId, ep.seasonNumber, ep.episodeNumber), true)
            }
            resolved.title.episodes.size
        }
    }

    private suspend fun applyRating(resolved: ResolvedImportTitle) {
        val rating = resolved.title.rating ?: return
        val existing = collectionApi.observeMembership(resolved.mediaId).first()
        if (existing?.rating == null) {
            collectionApi.setRating(resolved.mediaId, rating)
        }
    }
}

/**
 * Groups [PreviewImportUseCase]/[ApplyImportUseCase] and the [BackupRestorer] so
 * `ImportViewModel`'s constructor takes one parameter — the pattern
 * `SettingsActions`/`CollectionToggles` already follow. A Muviss backup takes
 * the restore path instead of preview/apply (EPIC 29, #72).
 */
class ImportActions(
    private val previewImportUseCase: PreviewImportUseCase,
    private val applyImportUseCase: ApplyImportUseCase,
    private val restorer: BackupRestorer,
) {
    fun isBackup(content: String): Boolean = ImportFormatDetector.isMuvissBackup(content)
    suspend fun summarizeBackup(content: String): BackupSummary = restorer.summarize(content)
    suspend fun restoreBackup(content: String): RestoreResult = restorer.restore(content)

    suspend fun preview(content: String, onProgress: ImportProgressListener = NO_PROGRESS): ImportPreview = previewImportUseCase(content, onProgress)
    suspend fun apply(preview: ImportPreview, onProgress: ImportProgressListener = NO_PROGRESS): ImportApplyResult = applyImportUseCase(preview, onProgress)
}
