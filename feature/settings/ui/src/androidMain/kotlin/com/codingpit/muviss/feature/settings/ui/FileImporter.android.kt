package com.codingpit.muviss.feature.settings.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CompletableDeferred

/**
 * Presents the system document picker (`ACTION_GET_CONTENT`, any file type —
 * an import can be `.json` or `.csv`) via [ActivityResultContracts.GetContent].
 * The contract is callback-shaped; [pickFile] bridges it to a suspend call
 * with a [CompletableDeferred] that the launcher's callback completes,
 * mirroring how a one-shot dialog result is normally threaded back into
 * Compose state, but exposed here as a plain suspend function so the caller
 * doesn't need to know about launchers at all.
 */
private class AndroidFileImporter(private val context: Context) : FileImporter {

    /** Set once [rememberFileImporter] has the launcher in hand — see that function's KDoc for the ordering. */
    lateinit var launcher: ActivityResultLauncher<String>
    private var pending: CompletableDeferred<PickedFile?>? = null

    override suspend fun pickFile(): PickedFile? {
        val deferred = CompletableDeferred<PickedFile?>()
        pending = deferred
        launcher.launch("*/*")
        return deferred.await()
    }

    fun onResult(uri: Uri?) {
        val deferred = pending
        pending = null
        if (uri == null) {
            deferred?.complete(null)
            return
        }
        val content = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        val name = displayNameOf(context, uri) ?: "import"
        deferred?.complete(content?.let { PickedFile(name, it) })
    }
}

private fun displayNameOf(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
    }
}.getOrNull()

/**
 * [rememberLauncherForActivityResult]'s launcher and this [FileImporter] are
 * mutually referential (the launcher's callback needs the importer to
 * deliver the result to; [AndroidFileImporter.pickFile] needs the launcher to
 * start the picker), so the importer is created first with an unset
 * `lateinit var launcher` and wired up on the same composition pass, before
 * either is ever used.
 */
@Composable
actual fun rememberFileImporter(): FileImporter {
    val context = LocalContext.current
    val importer = remember(context) { AndroidFileImporter(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> importer.onResult(uri) }
    importer.launcher = launcher
    return importer
}
