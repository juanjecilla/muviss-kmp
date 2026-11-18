package com.codingpit.muviss.feature.search.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.crash.launchInReporting
import com.codingpit.muviss.core.common.crash.launchReporting
import com.codingpit.muviss.core.common.todayEpochDay
import com.codingpit.muviss.core.designsystem.text.UiText
import com.codingpit.muviss.core.designsystem.text.toUiText
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.search.domain.Filmography
import com.codingpit.muviss.feature.search.domain.FilmographyOrganizer
import com.codingpit.muviss.feature.search.domain.PersonDetailUseCase
import com.codingpit.muviss.feature.search.ui.generated.resources.Res
import com.codingpit.muviss.feature.search.ui.generated.resources.error_generic
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.PersonDetails
import com.codingpit.muviss.models.PersonId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

data class PersonUiState(
    val loading: Boolean = true,
    val person: PersonDetails? = null,
    val filmography: Filmography? = null,
    /** Titles in the library, for the "in library" badge. */
    val libraryIds: Set<MediaId> = emptySet(),
    val error: UiText? = null,
)

/**
 * One person's page. The filmography is the source's, arranged by [FilmographyOrganizer]; the only thing
 * that is the user's own is which of those titles they already have, so that is read from the library.
 * Nothing here is stored: the page is fetched each time it opens.
 */
class PersonViewModel(
    private val personId: PersonId,
    private val loadPerson: PersonDetailUseCase,
    collection: CollectionApi,
    private val clock: AppClock,
) : ViewModel() {

    private val _state = MutableStateFlow(PersonUiState())
    val state: StateFlow<PersonUiState> = _state.asStateFlow()

    init {
        load()
        collection.observeSummaries()
            .onEach { library -> _state.update { it.copy(libraryIds = library.mapTo(mutableSetOf()) { entry -> entry.mediaId }) } }
            .launchInReporting(viewModelScope)
    }

    fun load() {
        viewModelScope.launchReporting {
            _state.update { it.copy(loading = true, error = null) }
            loadPerson(personId).fold(
                onSuccess = { person ->
                    _state.update { it.copy(loading = false, person = person, filmography = FilmographyOrganizer.organize(person, clock.todayEpochDay())) }
                },
                onFailure = { e -> _state.update { it.copy(loading = false, error = e.toUiText(UiText.Resource(Res.string.error_generic))) } },
            )
        }
    }
}
