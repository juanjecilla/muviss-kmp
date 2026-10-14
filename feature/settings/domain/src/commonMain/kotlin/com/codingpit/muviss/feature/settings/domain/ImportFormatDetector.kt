package com.codingpit.muviss.feature.settings.domain

/**
 * Auto-detects which [ImportSource] a picked file's raw content is: JSON
 * (`{`/`[`) is Trakt, unless it is Muviss's own backup ([isMuvissBackup]),
 * which is restored rather than imported and so is no [ImportSource]; CSV is sniffed
 * by header — TV Time's episodes/movies exports use column names Muviss's
 * own generic format doesn't, and vice versa (see each parser's KDoc for the
 * exact columns). Null means neither shape matched — the UI reports the file
 * as unrecognized rather than guessing.
 */
object ImportFormatDetector {

    private val TV_TIME_HEADER_MARKERS = setOf(
        "season_number",
        "episode_number",
        "series_name",
        "show_name",
        "movie_name",
    )

    fun detect(content: String): ImportSource? {
        if (isMuvissBackup(content)) return null
        val trimmed = content.trimStart()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return ImportSource.TRAKT

        val headerLine = content.lineSequence().firstOrNull { it.isNotBlank() } ?: return null
        val headers = CsvParser.parse(headerLine).firstOrNull()?.map { it.trim().lowercase() }?.toSet() ?: return null

        return when {
            headers.contains("title") && headers.contains("type") -> ImportSource.GENERIC_CSV
            headers.any { it in TV_TIME_HEADER_MARKERS } -> ImportSource.TV_TIME
            else -> null
        }
    }

    /**
     * A file Muviss's own export wrote, of any version (EPIC 29, #72). Keyed on
     * `exportedAtEpochMs`, the one field every version has carried and no
     * Trakt export does; it used to be read as Trakt and import nothing.
     */
    fun isMuvissBackup(content: String): Boolean {
        val trimmed = content.trimStart()
        return trimmed.startsWith("{") && BACKUP_MARKER.containsMatchIn(trimmed)
    }

    private val BACKUP_MARKER = Regex("\"exportedAtEpochMs\"\\s*:")
}
