package com.codingpit.muviss.core.designsystem.component

/**
 * How large an artwork is actually drawn, so [PosterImage] can ask TMDB's image
 * CDN for a matching width instead of decoding a `w500` poster into a 44dp
 * row. Widths are TMDB's published poster sizes; `w185` is also valid for
 * stills and profile photos.
 */
enum class PosterSize(val tmdbWidth: Int) {
    /** List rows, cast avatars and episode thumbnails: drawn well under 100dp wide. */
    Thumbnail(185),

    /** Poster grids: one of two to five columns. */
    Grid(342),

    /** Detail heroes and full-card surfaces. The size stored URLs already carry. */
    Detail(500),
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
    return TMDB_IMAGE_PREFIX + rest.replaceFirst(tmdbWidthSegment, "w${size.tmdbWidth}/")
}
