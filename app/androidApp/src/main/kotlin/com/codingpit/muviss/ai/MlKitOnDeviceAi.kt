package com.codingpit.muviss.ai

import android.content.Context
import android.os.Build
import android.util.Log
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.codingpit.muviss.core.designsystem.ai.OnDeviceAi
import com.codingpit.muviss.core.designsystem.ai.OnDeviceFeature
import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.genai.common.DownloadCallback
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.imagedescription.ImageDescriber
import com.google.mlkit.genai.imagedescription.ImageDescriberOptions
import com.google.mlkit.genai.imagedescription.ImageDescription
import com.google.mlkit.genai.imagedescription.ImageDescriptionRequest
import com.google.mlkit.genai.rewriting.Rewriter
import com.google.mlkit.genai.rewriting.RewriterOptions
import com.google.mlkit.genai.rewriting.Rewriting
import com.google.mlkit.genai.rewriting.RewritingRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [OnDeviceAi] on ML Kit GenAI: Gemini Nano through the AICore system service. Beta, API 26+, a short list of
 * devices (no emulators, no unlocked bootloaders), models downloaded on first use. Every failure becomes `null`.
 *
 * Image description is English only; shortening supports en, es, fr, de, it, ja and ko. See the talk's
 * docs/research.md (R1.3, R1.5) for where those facts come from.
 */
class MlKitOnDeviceAi(context: Context) : OnDeviceAi {
    private val appContext = context.applicationContext

    // Clients are cheap to create and hold native resources: create per request, always close.
    private fun describer(): ImageDescriber = ImageDescription.getClient(ImageDescriberOptions.builder(appContext).build())

    private fun rewriter(language: Int): Rewriter = Rewriting.getClient(
        RewriterOptions.builder(appContext)
            .setOutputType(RewriterOptions.OutputType.SHORTEN)
            .setLanguage(language)
            .build(),
    )

    override suspend fun isAvailable(feature: OnDeviceFeature): Boolean = guarded(false) {
        when (feature) {
            OnDeviceFeature.ImageDescription -> describer().use { it.checkFeatureStatus().await() } != FeatureStatus.UNAVAILABLE

            // The rewriter needs a language up front; English stands in for "can this device rewrite at all".
            OnDeviceFeature.Shorten -> rewriter(RewriterOptions.Language.ENGLISH).use { it.checkFeatureStatus().await() } != FeatureStatus.UNAVAILABLE
        }
    }

    override suspend fun describeImage(imageUrl: String): String? = guarded(null) {
        val bitmap = loadBitmap(imageUrl) ?: return@guarded null
        describer().use { client ->
            client.prepare()
            client.runInference(ImageDescriptionRequest.builder(bitmap).build()).await().description
        }
    }

    override suspend fun shorten(text: String, languageTag: String): String? = guarded(null) {
        val language = languageFor(languageTag) ?: return@guarded null
        rewriter(language).use { client ->
            client.prepare()
            client.runInference(RewritingRequest.builder(text.take(MAX_INPUT_CHARS)).build()).await().results.firstOrNull()?.text
        }
    }

    private suspend fun loadBitmap(url: String) = SingletonImageLoader.get(appContext)
        .execute(ImageRequest.Builder(appContext).data(url).allowHardware(false).build())
        .let { (it as? SuccessResult)?.image?.toBitmap() }

    private companion object {
        const val TAG = "MlKitOnDeviceAi"
        const val MIN_SDK = 26

        /** The model takes under 256 tokens; a synopsis of this many characters stays inside that. */
        const val MAX_INPUT_CHARS = 700

        fun languageFor(tag: String): Int? = when (tag.lowercase().substringBefore('-')) {
            "en" -> RewriterOptions.Language.ENGLISH
            "es" -> RewriterOptions.Language.SPANISH
            "fr" -> RewriterOptions.Language.FRENCH
            "de" -> RewriterOptions.Language.GERMAN
            "it" -> RewriterOptions.Language.ITALIAN
            "ja" -> RewriterOptions.Language.JAPANESE
            "ko" -> RewriterOptions.Language.KOREAN
            else -> null
        }
    }

    // A beta native model can fail in ways no signature declares; the screen must never crash on it.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> guarded(fallback: T, block: suspend () -> T): T = try {
        // The libraries need API 26 (the manifest overrides their minSdk): never load them below it.
        if (Build.VERSION.SDK_INT < MIN_SDK) fallback else block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: GenAiException) {
        Log.w(TAG, "GenAI error ${e.errorCode}: ${e.message}")
        fallback
    } catch (e: Exception) {
        Log.w(TAG, "On-device request failed", e)
        fallback
    }
}

// The two clients hold native resources and have `close()`, but share no interface to `use` them through.
private inline fun <R> ImageDescriber.use(block: (ImageDescriber) -> R): R = try {
    block(this)
} finally {
    close()
}

private inline fun <R> Rewriter.use(block: (Rewriter) -> R): R = try {
    block(this)
} finally {
    close()
}

/** Resolves when the listenable future does; cancelling the coroutine cancels the future. */
private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
    addListener(
        {
            try {
                cont.resume(get())
            } catch (e: ExecutionException) {
                cont.resumeWithException(e.cause ?: e)
            } catch (e: CancellationException) {
                cont.cancel(e)
            }
        },
        Runnable::run,
    )
    cont.invokeOnCancellation { cancel(false) }
}

private suspend fun ImageDescriber.prepare() {
    if (checkFeatureStatus().await() == FeatureStatus.DOWNLOADABLE) downloadAndWait { downloadFeature(it) }
}

private suspend fun Rewriter.prepare() {
    if (checkFeatureStatus().await() == FeatureStatus.DOWNLOADABLE) downloadAndWait { downloadFeature(it) }
}

/** Starts a model download and waits for it. A feature that is already DOWNLOADING makes `runInference` wait by itself. */
private suspend fun downloadAndWait(start: (DownloadCallback) -> ListenableFuture<Void>) = suspendCancellableCoroutine<Unit> { cont ->
    start(
        object : DownloadCallback {
            override fun onDownloadStarted(bytesToDownload: Long) = Unit

            override fun onDownloadProgress(totalBytesDownloaded: Long) = Unit

            override fun onDownloadCompleted() = cont.resume(Unit)

            override fun onDownloadFailed(e: GenAiException) = cont.resumeWithException(e)
        },
    )
}
