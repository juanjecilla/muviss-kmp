package com.codingpit.muviss.core.designsystem.ai

import androidx.compose.runtime.staticCompositionLocalOf

/** What a device can do with a model that runs on it: no network, no account, nothing leaves the phone. */
enum class OnDeviceFeature {
    /** A short description of an image (what a screen reader can say about a picture that has no alt text). */
    ImageDescription,

    /** A shorter version of a text, in the user's language. */
    Shorten,
}

/**
 * Seam for on-device generative models. Android implements it with ML Kit GenAI (Gemini Nano through AICore, beta);
 * every other target keeps [NoOnDeviceAi] and the buttons that need it never appear.
 *
 * Both calls return `null` for "not possible here": unsupported device or language, a failed download, a model
 * error. Callers show their own failure message; they never see an exception.
 */
interface OnDeviceAi {
    suspend fun isAvailable(feature: OnDeviceFeature): Boolean

    /** Describes the image at [imageUrl]. The model answers in English only (ML Kit GenAI, beta). */
    suspend fun describeImage(imageUrl: String): String?

    /** Shortens [text], written in [languageTag] ("en", "es"…). `null` when that language is not supported. */
    suspend fun shorten(text: String, languageTag: String): String?
}

/** The default: nothing available. */
object NoOnDeviceAi : OnDeviceAi {
    override suspend fun isAvailable(feature: OnDeviceFeature): Boolean = false

    override suspend fun describeImage(imageUrl: String): String? = null

    override suspend fun shorten(text: String, languageTag: String): String? = null
}

/** Provided by the platform host (`MainActivity` on Android); screens read it and degrade to nothing. */
val LocalOnDeviceAi = staticCompositionLocalOf<OnDeviceAi> { NoOnDeviceAi }
