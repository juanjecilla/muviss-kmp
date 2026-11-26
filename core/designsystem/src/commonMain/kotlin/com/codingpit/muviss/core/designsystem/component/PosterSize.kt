package com.codingpit.muviss.core.designsystem.component

/**
 * How large an artwork is actually drawn, so [PosterImage] can ask TMDB's image
 * CDN for a matching width instead of decoding a `w500` poster into a 44dp
 * row. Each segment is a size TMDB lists for the artwork it is applied to:
 * poster sizes for posters, still sizes for [Still] (see /configuration).
 */
enum class PosterSize(val tmdbSegment: String) {
    /** List rows, cast avatars and episode thumbnails: drawn well under 100dp wide. */
    Thumbnail("w185"),

    /** Poster grids: one of two to five columns. */
    Grid("w342"),

    /** Detail heroes and full-card surfaces. The size stored poster URLs already carry. */
    Detail("w500"),

    /**
     * An episode still as a full-width hero. TMDB does not list `w500` for stills; it lists `w300` (blurry at a
     * phone's hero width) and `original`, so the hero asks for `original`. Stored still URLs are `w300`, which
     * thumbnails rewrite to `w185`.
     */
    Still("original"),
}

private const val TMDB_IMAGE_PREFIX = "https://image.tmdb.org/t/p/"
private val tmdbWidthSegment = Regex("^w\\d+/")

/**
 * Rewrites the width segment of a TMDB image URL (`.../t/p/w500/abc.jpg`) to
 * [size]. Snapshots persist the URL as fetched (w500), so this is what lets
 * every already-saved title benefit without a migration or a refetch. A URL
 * that is not a TMDB image URL, or has no width segment (`original`), is
 * returned unchanged.
 */
fun sizedImageUrl(url: String, size: PosterSize): String {
    if (!url.startsWith(TMDB_IMAGE_PREFIX)) return url
    val rest = url.removePrefix(TMDB_IMAGE_PREFIX)
    if (!tmdbWidthSegment.containsMatchIn(rest)) return url
    return TMDB_IMAGE_PREFIX + rest.replaceFirst(tmdbWidthSegment, "${size.tmdbSegment}/")
}
