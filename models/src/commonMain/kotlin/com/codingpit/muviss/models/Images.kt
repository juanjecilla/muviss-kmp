package com.codingpit.muviss.models

/** What a Gallery shows. Never mixed in one Gallery: aspect ratios differ too much to page between. */
enum class ImageKind {
    Backdrop,
    Poster,
    Still,
}

/** One piece of artwork at the size the Image Viewer pages through. */
data class TitleImage(
    val url: String,
    val aspectRatio: Float = 0f,
) {
    /**
     * The file's own name, with no size prefix: the same artwork is `w780/abc.jpg` on the screen
     * the person tapped and `w1280/abc.jpg` in the Gallery, and this is what tells them apart from
     * a different image.
     */
    val fileKey: String get() = url.substringAfterLast('/')
}

/** Every Gallery a source offers for one title (or, for stills, one episode). */
data class TitleImages(
    val backdrops: List<TitleImage> = emptyList(),
    val posters: List<TitleImage> = emptyList(),
    val stills: List<TitleImage> = emptyList(),
) {
    fun of(kind: ImageKind): List<TitleImage> = when (kind) {
        ImageKind.Backdrop -> backdrops
        ImageKind.Poster -> posters
        ImageKind.Still -> stills
    }
}
