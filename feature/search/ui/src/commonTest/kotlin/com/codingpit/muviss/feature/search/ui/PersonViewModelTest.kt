@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.search.ui

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.search.domain.PersonDetailUseCase
import com.codingpit.muviss.feature.search.domain.SearchRepository
import com.codingpit.muviss.models.EpisodeDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MetadataError
import com.codingpit.muviss.models.PersonCredit
import com.codingpit.muviss.models.PersonDetails
import com.codingpit.muviss.models.PersonId
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Everything but `person` is the episode fake's: this suite never calls it. */
private class FakePersonRepo(var result: Result<PersonDetails>) : SearchRepository by FakeEpisodeRepo(Result.failure<EpisodeDetails>(IllegalStateException("unused"))) {
    var calls = 0
    override suspend fun person(id: PersonId): Result<PersonDetails> {
        calls++
        return result
    }
}

class PersonViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val id = PersonId.tmdb("6193")
    private val inception = MediaSummary(MediaId.tmdbMovie("27205"), "Inception")
    private val person = PersonDetails(
        id = id,
        name = "Some Person",
        knownForDepartment = "Acting",
        credits = listOf(PersonCredit(inception, character = "Cobb", acting = true, releaseEpochDay = 14_000L, voteCount = 100)),
    )

    private object DayZero : AppClock {
        override fun nowEpochMs(): Long = 20_000L * 86_400_000L
    }

    private fun viewModel(repo: FakePersonRepo, collection: FakeSearchCollectionApi = FakeSearchCollectionApi()) = PersonViewModel(id, PersonDetailUseCase(repo), collection, DayZero)

    @Test
    fun it_loads_the_person_and_organizes_their_filmography() = runTest {
        val vm = viewModel(FakePersonRepo(Result.success(person)))
        advanceUntilIdle()

        assertEquals(person, vm.state.value.person)
        assertFalse(vm.state.value.loading)
        assertEquals(listOf(inception.id), vm.state.value.filmography!!.acting.map { it.title.id })
    }

    @Test
    fun titles_in_the_library_are_known_and_follow_it() = runTest {
        val collection = FakeSearchCollectionApi()
        val vm = viewModel(FakePersonRepo(Result.success(person)), collection)
        advanceUntilIdle()
        assertTrue(vm.state.value.libraryIds.isEmpty())

        collection.library.value = listOf(CollectionSummary(inception.id, "Inception", null, WatchStatus.NOT_STARTED))
        advanceUntilIdle()

        assertEquals(setOf(inception.id), vm.state.value.libraryIds)
    }

    @Test
    fun a_failure_surfaces_and_a_retry_recovers() = runTest {
        val repo = FakePersonRepo(Result.failure(MetadataError.Offline()))
        val vm = viewModel(repo)
        advanceUntilIdle()
        assertNotNull(vm.state.value.error)
        assertNull(vm.state.value.person)

        repo.result = Result.success(person)
        vm.load()
        advanceUntilIdle()

        assertNull(vm.state.value.error)
        assertEquals(person, vm.state.value.person)
        assertEquals(2, repo.calls)
    }

    @Test
    fun a_raw_exception_never_reaches_the_screen() = runTest {
        val vm = viewModel(FakePersonRepo(Result.failure(IllegalStateException("Timeout for https://api.themoviedb.org/3/person/1?api_key=SECRET"))))
        advanceUntilIdle()

        assertFalse(vm.state.value.error.text()!!.contains("SECRET"))
    }
}
