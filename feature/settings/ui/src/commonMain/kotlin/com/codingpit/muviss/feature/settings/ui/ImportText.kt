package com.codingpit.muviss.feature.settings.ui

import com.codingpit.muviss.core.designsystem.text.UiText
import com.codingpit.muviss.core.designsystem.text.toUiText
import com.codingpit.muviss.feature.settings.domain.FailedImportTitle
import com.codingpit.muviss.feature.settings.domain.ImportFileError
import com.codingpit.muviss.feature.settings.domain.UnresolvedReason
import com.codingpit.muviss.feature.settings.ui.generated.resources.Res
import com.codingpit.muviss.feature.settings.ui.generated.resources.error_import
import com.codingpit.muviss.feature.settings.ui.generated.resources.import_file_newer_backup
import com.codingpit.muviss.feature.settings.ui.generated.resources.import_file_no_importer
import com.codingpit.muviss.feature.settings.ui.generated.resources.import_file_unreadable_backup
import com.codingpit.muviss.feature.settings.ui.generated.resources.import_file_unrecognized
import com.codingpit.muviss.feature.settings.ui.generated.resources.unresolved_no_external_id
import com.codingpit.muviss.feature.settings.ui.generated.resources.unresolved_no_match
import com.codingpit.muviss.feature.settings.ui.generated.resources.unresolved_unknown_media_type

// The import domain reports reasons, not copy; these word them (#219).

internal fun UnresolvedReason.toUiText(): UiText = UiText.Resource(
    when (this) {
        UnresolvedReason.NoExternalId -> Res.string.unresolved_no_external_id
        UnresolvedReason.UnknownMediaType -> Res.string.unresolved_unknown_media_type
        UnresolvedReason.NoMatch -> Res.string.unresolved_no_match
    },
)

internal fun ImportFileError.toUiText(): UiText = UiText.Resource(
    when (this) {
        ImportFileError.Unrecognized -> Res.string.import_file_unrecognized
        ImportFileError.NoImporter -> Res.string.import_file_no_importer
        ImportFileError.UnreadableBackup -> Res.string.import_file_unreadable_backup
        ImportFileError.NewerBackupVersion -> Res.string.import_file_newer_backup
    },
)

internal fun FailedImportTitle.reasonText(): UiText = error.toUiText(UiText.Resource(Res.string.error_import))
