package com.codingpit.muviss.feature.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Documented stub, not a real picker (EPIC 18 explicitly allows this: "iOS
 * best-effort UIDocumentPicker or documented stub"). A real implementation
 * needs `UIDocumentPickerViewController` driven through its delegate
 * protocol — an `NSObject` subclass implementing
 * `UIDocumentPickerDelegateProtocol`, security-scoped resource access
 * (`startAccessingSecurityScopedResource`) around the read, and bridging the
 * delegate callback back to this suspend function the same way
 * `FileImporter.android.kt` bridges its activity-result launcher callback.
 * That is a meaningfully larger unit of native-interop work than this EPIC's
 * budget covers here; [pickFile] always returns null (import is unavailable
 * on iOS today) rather than shipping a partially-working picker. Import from
 * iOS is exercised via the desktop/Android/web pickers in the meantime — see
 * `docs/IMPORT.md`.
 */
private class IosFileImporter : FileImporter {
    override suspend fun pickFile(): PickedFile? = null
}

@Composable
actual fun rememberFileImporter(): FileImporter = remember { IosFileImporter() }
