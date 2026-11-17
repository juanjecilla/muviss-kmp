package com.codingpit.muviss.feature.search.domain

import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.ImageKind
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.TitleImage
import com.codingpit.muviss.models.TitleImages
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LoadGalleryUseCaseTest {

    private val show = MediaId.tmdbTv("1399")
    private val seed = image("w780", "two.jpg")

    private fun image(size: String, file: String) = TitleImage("https://image.tmdb.org/t/p/$size/$file")

    private class Repo(
        private val images: Result<TitleImages>,
        private val stills: Result<TitleImages> = Result.success(TitleImages()),
    ) : SearchRepository {
        var imageCalls = 0
        var stillCalls = 0

        override suspend fun search(query: String, page: Int) = error("unused")
        override suspend fun trending() = error("unused")
        override suspend fun details(id: MediaId) = error("unused")
        override suspend fun discover(type: MediaType, page: Int, genreId: String?, excludedGenreIds: Set<String>, fresh: Boolean) = error("unused")
        override suspend fun genres(type: MediaType, fresh: Boolean) = error("unused")
        override suspend fun watchProviders(id: MediaId) = error("unused")
        override suspend fun recommendations(id: MediaId, page: Int, fresh: Boolean) = error("unused")
        override suspend fun similar(id: MediaId, page: Int, fresh: Boolean) = error("unused")
        override suspend fun episodeDetails(episodeId: EpisodeId) = error("unused")

        override suspend fun images(id: MediaId): Result<TitleImages> = images.also { imageCalls++ }

        override suspend fun episodeImages(episodeId: EpisodeId): Result<TitleImages> = stills.also { stillCalls++ }
    }

    @Test
    fun it_opens_on_the_seed_alone_before_anything_is_fetched() = runTest {
        val repo = Repo(Result.failure(IllegalStateException("offline")))

        val emissions = LoadGalleryUseCase(repo)(show, null, ImageKind.Backdrop, seed).toList()

        assertEquals(listOf(Gallery(listOf(seed))), emissions)
    }

    @Test
    fun the_seed_stays_first_and_comes_back_at_the_larger_size() = runTest {
        val full = TitleImages(backdrops = listOf(image("w1280", "one.jpg"), image("w1280", "two.jpg"), image("w1280", "three.jpg")))

        val emissions = LoadGalleryUseCase(Repo(Result.success(full)))(show, null, ImageKind.Backdrop, seed).toList()

        assertEquals(
            listOf(image("w1280", "two.jpg"), image("w1280", "one.jpg"), image("w1280", "three.jpg")),
            emissions.last().images,
        )
    }

    @Test
    fun a_seed_the_source_does_not_list_is_still_first() = runTest {
        val full = TitleImages(backdrops = listOf(image("w1280", "one.jpg")))

        val emissions = LoadGalleryUseCase(Repo(Result.success(full)))(show, null, ImageKind.Backdrop, seed).toList()

        assertEquals(listOf(seed, image("w1280", "one.jpg")), emissions.last().images)
    }

    @Test
    fun an_image_listed_twice_appears_once() = runTest {
        val full = TitleImages(posters = listOf(image("w780", "one.jpg"), image("w780", "one.jpg")))

        val emissions = LoadGalleryUseCase(Repo(Result.success(full)))(show, null, ImageKind.Poster, image("w500", "zero.jpg")).toList()

        assertEquals(2, emissions.last().images.size)
    }

    @Test
    fun only_the_asked_for_kind_is_used() = runTest {
        val full = TitleImages(backdrops = listOf(image("w1280", "wide.jpg")), posters = listOf(image("w780", "tall.jpg")))

        val emissions = LoadGalleryUseCase(Repo(Result.success(full)))(show, null, ImageKind.Poster, seed).toList()

        assertEquals(listOf(seed, image("w780", "tall.jpg")), emissions.last().images)
    }

    @Test
    fun an_episode_gallery_asks_for_stills_not_the_titles_art() = runTest {
        val repo = Repo(Result.success(TitleImages(backdrops = listOf(image("w1280", "wide.jpg")))), stills = Result.success(TitleImages(stills = listOf(image("w1280", "still.jpg")))))

        val emissions = LoadGalleryUseCase(repo)(show, EpisodeId(show, 1, 2), ImageKind.Still, seed).toList()

        assertEquals(0, repo.imageCalls)
        assertEquals(1, repo.stillCalls)
        assertEquals(listOf(seed, image("w1280", "still.jpg")), emissions.last().images)
    }

    @Test
    fun a_source_with_nothing_leaves_the_gallery_as_the_seed() = runTest {
        val emissions = LoadGalleryUseCase(Repo(Result.success(TitleImages())))(show, null, ImageKind.Backdrop, seed).toList()

        assertEquals(1, emissions.size)
    }
}
