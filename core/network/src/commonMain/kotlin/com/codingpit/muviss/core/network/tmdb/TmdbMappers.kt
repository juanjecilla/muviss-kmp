package com.codingpit.muviss.core.network.tmdb

import com.codingpit.muviss.models.Credit
import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeCredit
import com.codingpit.muviss.models.EpisodeDetails
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaAnchors
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.PagedResult
import com.codingpit.muviss.models.PersonAnchors
import com.codingpit.muviss.models.PersonCredit
import com.codingpit.muviss.models.PersonDetails
import com.codingpit.muviss.models.PersonId
import com.codingpit.muviss.models.ProductionStatus
import com.codingpit.muviss.models.Season
import com.codingpit.muviss.models.TitleImage
import com.codingpit.muviss.models.TitleImages
import com.codingpit.muviss.models.WatchProvider
import com.codingpit.muviss.models.WatchProviders

internal object TmdbMapper {
    /** Two characters at most per person: a voice actor with eleven roles is a wall of text on a 64dp card. */
    private const val MAX_CHARACTERS_SHOWN = 2

    /** TMDB's "Talk" (10767) and "News" (10763) TV genre ids. */
    private val TALK_AND_NEWS_GENRES = setOf(10767, 10763)

    const val IMAGE_BASE = "https://image.tmdb.org/t/p/w500"

    // Episode stills are stored at w300, a size TMDB lists for stills (/configuration: w92, w185, w300, original).
    // w500 is not listed for them. Rows rewrite it to w185, and the hero to `original` (PosterSize.Still).
    const val STILL_BASE = "https://image.tmdb.org/t/p/w300"

    // Provider logos render small (a couple dozen dp); w92 is TMDB's smallest
    // non-thumbnail size and keeps the "Where to watch" row light.
    const val LOGO_BASE = "https://image.tmdb.org/t/p/w92"

    // Backdrops render edge-to-edge behind the detail hero; w780 is the
    // smallest size that stays sharp at expanded widths.
    const val BACKDROP_BASE = "https://image.tmdb.org/t/p/w780"

    // The Image Viewer pages through full-screen art. Each kind uses a size TMDB lists for it (see /configuration):
    // w1280 for backdrops and w780 for posters. Stills list only w92, w185, w300 and `original`, and w300 is smaller than
    // the w500 hero the person tapped, so a still is shown at `original`. It loads only when the viewer opens, and the
    // hero keeps the smaller copy meanwhile. The episode hero draws `original` too (PosterSize.Still), so it shares this copy.
    const val GALLERY_BACKDROP_BASE = "https://image.tmdb.org/t/p/w1280"
    const val GALLERY_POSTER_BASE = "https://image.tmdb.org/t/p/w780"
    const val GALLERY_STILL_BASE = "https://image.tmdb.org/t/p/original"

    /** Best-voted first (the sort is stable, so TMDB's own order breaks ties), one entry per file. */
    fun imagesToModel(dto: TmdbImagesDto): TitleImages = TitleImages(
        backdrops = galleryOf(dto.backdrops, GALLERY_BACKDROP_BASE),
        posters = galleryOf(dto.posters, GALLERY_POSTER_BASE),
        stills = galleryOf(dto.stills, GALLERY_STILL_BASE),
    )

    private fun galleryOf(images: List<TmdbImageDto>, base: String): List<TitleImage> = images
        .distinctBy { it.filePath }
        .sortedByDescending { it.voteAverage }
        .map { TitleImage(url = base + it.filePath, aspectRatio = it.aspectRatio.toFloat()) }

    fun imageUrl(path: String?): String? = path?.let { IMAGE_BASE + it }

    fun stillUrl(path: String?): String? = path?.let { STILL_BASE + it }

    fun backdropUrl(path: String?): String? = path?.let { BACKDROP_BASE + it }

    fun logoUrl(path: String?): String? = path?.let { LOGO_BASE + it }

    fun yearOf(date: String?): Int? = date?.take(4)?.toIntOrNull()

    /**
     * Parses a TMDB `air_date` (`yyyy-MM-dd`) into an epoch day (days since
     * 1970-01-01, matching [com.codingpit.muviss.models.Episode.airDateEpochDay]'s
     * convention), or null if [airDate] is null or malformed. No date library
     * is used — this is common code and must run on every target — so the day
     * count is computed directly via the standard proleptic-Gregorian
     * days-from-civil algorithm (Howard Hinnant's `days_from_civil`).
     */
    fun airDateToEpochDay(airDate: String?): Long? {
        if (airDate == null) return null
        val parts = airDate.split("-")
        if (parts.size != 3) return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val day = parts[2].toIntOrNull() ?: return null
        return epochDayFromDate(year, month, day)
    }

    private fun epochDayFromDate(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - ERA_YEAR_ADJUST) / DAYS_PER_ERA_YEARS
        val yearOfEra = y - era * DAYS_PER_ERA_YEARS // [0, 399]
        val monthIndex = if (month > 2) month - 3 else month + 9 // [0, 11], March-based
        val dayOfYear = (MONTH_TO_DAYS_NUMERATOR * monthIndex + 2) / 5 + day - 1 // [0, 365]
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear // [0, 146096]
        return era.toLong() * DAYS_PER_ERA + dayOfEra.toLong() - DAYS_FROM_ERA_ZERO_TO_UNIX_EPOCH
    }

    private const val ERA_YEAR_ADJUST = 399
    private const val DAYS_PER_ERA_YEARS = 400
    private const val MONTH_TO_DAYS_NUMERATOR = 153
    private const val DAYS_PER_ERA = 146_097L
    private const val DAYS_FROM_ERA_ZERO_TO_UNIX_EPOCH = 719_468L

    fun tvProductionStatus(status: String?): ProductionStatus = when (status?.lowercase()) {
        "returning series", "in production", "planned", "pilot" -> ProductionStatus.RETURNING
        "ended" -> ProductionStatus.ENDED
        "canceled", "cancelled" -> ProductionStatus.CANCELED
        else -> ProductionStatus.UNKNOWN
    }

    /** Maps a search/trending row; returns null for non movie/tv rows (e.g. people). */
    fun resultToSummary(dto: TmdbResultDto): MediaSummary? = when (dto.mediaType) {
        "movie" -> discoverResultToSummary(MediaType.MOVIE, dto)
        "tv" -> discoverResultToSummary(MediaType.TV, dto)
        else -> null
    }

    /**
     * Maps a `/discover/{movie|tv}` row. Unlike search/trending rows, discover
     * rows carry no `media_type` — the endpoint called already fixes [type].
     */
    fun discoverResultToSummary(type: MediaType, dto: TmdbResultDto): MediaSummary = when (type) {
        MediaType.MOVIE -> dto.toSummary(MediaId.tmdbMovie(dto.id.toString()), dto.title, dto.releaseDate)
        MediaType.TV -> dto.toSummary(MediaId.tmdbTv(dto.id.toString()), dto.name, dto.firstAirDate)
    }

    private fun TmdbResultDto.toSummary(id: MediaId, title: String?, date: String?): MediaSummary = MediaSummary(
        id = id,
        title = title.orEmpty(),
        year = yearOf(date),
        posterUrl = imageUrl(posterPath),
        overview = overview,
        rating = voteAverage,
        genreIds = genreIds.map { it.toString() },
    )

    /** Maps a `/search/multi` page, dropping non movie/tv rows (e.g. people). */
    fun searchPageToPagedResult(dto: TmdbPageDto): PagedResult<MediaSummary> = PagedResult(
        items = dto.results.mapNotNull(::resultToSummary),
        page = dto.page,
        totalPages = dto.totalPages,
    )

    /** Maps a `/discover/{movie|tv}` page, already fixed to a single [type]. */
    fun discoverPageToPagedResult(type: MediaType, dto: TmdbPageDto): PagedResult<MediaSummary> = PagedResult(
        items = dto.results.map { discoverResultToSummary(type, it) },
        page = dto.page,
        totalPages = dto.totalPages,
    )

    fun genreListToModels(dto: TmdbGenreListDto): List<Genre> = dto.genres.map { Genre(it.id.toString(), it.name) }

    /** Maps one region's entry from `/{movie|tv}/{id}/watch/providers`; null (region not offered) maps to empty. */
    fun watchProvidersToModel(dto: TmdbWatchProviderRegionDto?): WatchProviders = if (dto == null) {
        WatchProviders()
    } else {
        WatchProviders(
            flatrate = dto.flatrate.map { it.toModel() },
            rent = dto.rent.map { it.toModel() },
            buy = dto.buy.map { it.toModel() },
        )
    }

    private fun TmdbWatchProviderDto.toModel() = WatchProvider(
        id = providerId.toString(),
        name = providerName,
        logoUrl = logoUrl(logoPath),
    )

    fun movieToDetails(dto: TmdbMovieDetailDto): MediaDetails {
        val id = MediaId.tmdbMovie(dto.id.toString())
        return MediaDetails(
            summary = MediaSummary(
                id = id,
                title = dto.title,
                year = yearOf(dto.releaseDate),
                posterUrl = imageUrl(dto.posterPath),
                overview = dto.overview,
                rating = dto.voteAverage,
                genreIds = dto.genres.mapNotNull { it.id?.toString() },
            ),
            anchors = anchorsOf(dto.externalIds),
            genres = dto.genres.map { it.name },
            runtimeMinutes = dto.runtime,
            productionStatus = ProductionStatus.RELEASED,
            seasons = emptyList(),
            backdropUrl = backdropUrl(dto.backdropPath),
            cast = dto.credits?.cast.orEmpty().sortedBy { it.order }.mapNotNull { it.toCredit() },
            crew = dto.credits?.crew.orEmpty().mapNotNull { it.toCredit() },
        )
    }

    fun tvToDetails(dto: TmdbTvDetailDto, seasons: List<Season>): MediaDetails {
        val id = MediaId.tmdbTv(dto.id.toString())
        return MediaDetails(
            summary = MediaSummary(
                id = id,
                title = dto.name,
                year = yearOf(dto.firstAirDate),
                posterUrl = imageUrl(dto.posterPath),
                overview = dto.overview,
                rating = dto.voteAverage,
                genreIds = dto.genres.mapNotNull { it.id?.toString() },
            ),
            anchors = anchorsOf(dto.externalIds),
            genres = dto.genres.map { it.name },
            runtimeMinutes = null,
            productionStatus = tvProductionStatus(dto.status),
            seasons = seasons,
            backdropUrl = backdropUrl(dto.backdropPath),
            cast = dto.aggregateCredits?.cast.orEmpty().sortedBy { it.order }.mapNotNull { it.toCredit() },
            // A show's creators come first: they are what "Created by" reads, and `created_by` is not in the credits.
            crew = (dto.createdBy.map { it.toCredit() } + dto.aggregateCredits?.crew.orEmpty().mapNotNull { it.toCredit() }),
        )
    }

    /**
     * A person's page. [fallbackBiography] is the English one, asked for only when the biography in the
     * app's language is blank. Appearances as themselves on a talk or news programme are left out of
     * the credits: a famous actor has thousands, and they bury the work the person is known for.
     */
    fun personToDetails(dto: TmdbPersonDto, fallbackBiography: String? = null): PersonDetails {
        val credits = dto.combinedCredits
        return PersonDetails(
            id = PersonId.tmdb(dto.id.toString()),
            name = dto.name,
            profileUrl = imageUrl(dto.profilePath),
            biography = dto.biography?.takeIf { it.isNotBlank() } ?: fallbackBiography?.takeIf { it.isNotBlank() },
            birthday = dto.birthday?.takeIf { it.isNotBlank() },
            deathday = dto.deathday?.takeIf { it.isNotBlank() },
            placeOfBirth = dto.placeOfBirth?.takeIf { it.isNotBlank() },
            knownForDepartment = dto.knownForDepartment?.takeIf { it.isNotBlank() },
            anchors = PersonAnchors(
                imdbId = dto.externalIds?.imdbId?.takeIf { it.isNotBlank() },
                wikidataId = dto.externalIds?.wikidataId?.takeIf { it.isNotBlank() },
            ),
            credits = credits?.cast.orEmpty().mapNotNull { it.toPersonCredit(acting = true) } +
                credits?.crew.orEmpty().mapNotNull { it.toPersonCredit(acting = false) },
        )
    }

    private fun TmdbPersonCreditDto.toPersonCredit(acting: Boolean): PersonCredit? {
        val type = when (mediaType) {
            "movie" -> MediaType.MOVIE
            "tv" -> MediaType.TV
            else -> return null
        }
        val summary = discoverResultToSummary(
            type,
            TmdbResultDto(id, mediaType, title, name, posterPath, overview, releaseDate, firstAirDate, voteAverage, genreIds),
        )
        if (summary.title.isBlank()) return null
        if (acting && type == MediaType.TV && genreIds.any { it in TALK_AND_NEWS_GENRES }) return null
        return PersonCredit(
            title = summary,
            character = character?.takeIf { it.isNotBlank() },
            job = job?.takeIf { it.isNotBlank() },
            acting = acting,
            releaseEpochDay = airDateToEpochDay(if (type == MediaType.MOVIE) releaseDate else firstAirDate),
            voteCount = voteCount,
            episodeCount = episodeCount?.takeIf { it > 0 },
        )
    }

    private fun anchorsOf(ids: TmdbExternalIdsDto?) = MediaAnchors(
        imdbId = ids?.imdbId?.takeIf { it.isNotBlank() },
        wikidataId = ids?.wikidataId?.takeIf { it.isNotBlank() },
    )

    // TMDB sends a nameless row now and then; a Credit that names nobody cannot be shown or opened.
    private fun TmdbCastDto.toCredit(): Credit? = name.takeIf { it.isNotBlank() }?.let {
        Credit(PersonId.tmdb(id.toString()), it, imageUrl(profilePath), character = character?.takeIf { c -> c.isNotBlank() })
    }

    private fun TmdbCrewDto.toCredit(): Credit? = name.takeIf { it.isNotBlank() }?.let {
        Credit(PersonId.tmdb(id.toString()), it, imageUrl(profilePath), job = job?.takeIf { j -> j.isNotBlank() }, department = department?.takeIf { d -> d.isNotBlank() })
    }

    private fun TmdbCreatorDto.toCredit() = Credit(PersonId.tmdb(id.toString()), name, imageUrl(profilePath), job = Credit.CREATOR_JOB, department = Credit.CREATOR_DEPARTMENT)

    private fun TmdbAggregateCastDto.toCredit(): Credit? = name.takeIf { it.isNotBlank() }?.let {
        val roles = roles.sortedByDescending { r -> r.episodeCount ?: 0 }
        Credit(
            personId = PersonId.tmdb(id.toString()),
            name = it,
            profileUrl = imageUrl(profilePath),
            character = roles.mapNotNull { r -> r.character?.takeIf { c -> c.isNotBlank() } }.distinct().take(MAX_CHARACTERS_SHOWN).joinToString(" / ").ifEmpty { null },
            episodeCount = (totalEpisodeCount ?: roles.sumOf { r -> r.episodeCount ?: 0 }).takeIf { n -> n > 0 },
        )
    }

    private fun TmdbAggregateCrewDto.toCredit(): Credit? = name.takeIf { it.isNotBlank() }?.let {
        val jobs = jobs.sortedByDescending { j -> j.episodeCount ?: 0 }
        Credit(
            personId = PersonId.tmdb(id.toString()),
            name = it,
            profileUrl = imageUrl(profilePath),
            job = jobs.mapNotNull { j -> j.job?.takeIf { n -> n.isNotBlank() } }.distinct().joinToString(", ").ifEmpty { null },
            department = department?.takeIf { d -> d.isNotBlank() },
            episodeCount = (totalEpisodeCount ?: jobs.sumOf { j -> j.episodeCount ?: 0 }).takeIf { n -> n > 0 },
        )
    }

    /**
     * Picks the right row out of a `/find/{external_id}` response for
     * [type] (the movie or TV list, whichever the caller asked for), or —
     * when [type] is unknown — whichever list has a match, movies first.
     * Used by [TmdbProvider.findByExternalId] (EPIC 18 import id-mapping).
     */
    fun findResponseToSummary(dto: TmdbFindResponseDto, type: MediaType?): MediaSummary? = when (type) {
        MediaType.MOVIE -> dto.movieResults.firstOrNull()?.let { discoverResultToSummary(MediaType.MOVIE, it) }

        MediaType.TV -> dto.tvResults.firstOrNull()?.let { discoverResultToSummary(MediaType.TV, it) }

        null -> dto.movieResults.firstOrNull()?.let { discoverResultToSummary(MediaType.MOVIE, it) }
            ?: dto.tvResults.firstOrNull()?.let { discoverResultToSummary(MediaType.TV, it) }
    }

    fun seasonToModel(show: MediaId, dto: TmdbSeasonDetailDto): Season = Season(
        number = dto.seasonNumber,
        name = dto.name,
        episodes = dto.episodes.map { ep ->
            Episode(
                id = EpisodeId(show, ep.seasonNumber, ep.episodeNumber),
                seasonNumber = ep.seasonNumber,
                episodeNumber = ep.episodeNumber,
                name = ep.name,
                airDateEpochDay = airDateToEpochDay(ep.airDate),
                stillUrl = stillUrl(ep.stillPath),
                runtimeMinutes = ep.runtime,
            )
        },
    )

    fun episodeDetailToModel(episodeId: EpisodeId, dto: TmdbEpisodeDetailDto): EpisodeDetails = EpisodeDetails(
        id = episodeId,
        name = dto.name,
        seasonNumber = dto.seasonNumber,
        episodeNumber = dto.episodeNumber,
        overview = dto.overview?.takeIf { it.isNotBlank() },
        airDateEpochDay = airDateToEpochDay(dto.airDate),
        stillUrl = stillUrl(dto.stillPath),
        runtimeMinutes = dto.runtime,
        // TMDB returns 0.0 for "nobody has voted", which would render as a
        // real score of zero rather than as an absence.
        voteAverage = dto.voteAverage?.takeIf { it > 0.0 },
        guestStars = dto.guestStars.map { it.toCredit() },
        crew = dto.crew.map { it.toCredit() },
    )

    private fun TmdbEpisodeCreditDto.toCredit(): EpisodeCredit = EpisodeCredit(
        name = name,
        personId = id?.let { PersonId.tmdb(it.toString()) },
        character = character?.takeIf { it.isNotBlank() },
        job = job?.takeIf { it.isNotBlank() },
        profileUrl = imageUrl(profilePath),
    )
}
