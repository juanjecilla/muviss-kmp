package com.codingpit.muviss.feature.search.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.core.common.crash.launchInReporting
import com.codingpit.muviss.feature.search.domain.LoadGalleryUseCase
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.ImageKind
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.TitleImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach

/** What the Image Viewer is opened on; one object because Koin's parameter destructuring stops at three. */
data class ImageViewerArgs(
    val mediaId: MediaId,
    val episodeId: EpisodeId?,
    val kind: ImageKind,
    val seed: TitleImage,
)

/**
 * The Gallery behind the Image Viewer. Starts as the tapped image alone, so the viewer opens
 * instantly and works offline, and grows to the full set when the source answers.
 */
class ImageViewerViewModel(
    mediaId: MediaId,
    episodeId: EpisodeId?,
    val kind: ImageKind,
    seed: TitleImage,
    loadGallery: LoadGalleryUseCase,
) : ViewModel() {

    private val _images = MutableStateFlow(listOf(seed))
    val images: StateFlow<List<TitleImage>> = _images.asStateFlow()

    init {
        loadGallery(mediaId, episodeId, kind, seed)
            .onEach { _images.value = it.images }
            .launchInReporting(viewModelScope)
    }
}
