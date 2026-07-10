package com.codingpit.muviss.feature.profile.data.di

import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.feature.profile.api.ProfileApi
import com.codingpit.muviss.feature.profile.data.DefaultProfileApi
import com.codingpit.muviss.feature.profile.data.SqlDelightProfileRepository
import com.codingpit.muviss.feature.profile.domain.ObserveProfileStatsUseCase
import com.codingpit.muviss.feature.profile.domain.ObserveProfileUseCase
import com.codingpit.muviss.feature.profile.domain.ProfileActions
import com.codingpit.muviss.feature.profile.domain.ProfileRepository
import com.codingpit.muviss.feature.profile.domain.SetAvatarUseCase
import com.codingpit.muviss.feature.profile.domain.SetDisplayNameUseCase
import org.koin.core.module.Module
import org.koin.dsl.module

/** Repository + use-case + [ProfileApi] bindings for the profile feature. */
val profileDataModule: Module = module {
    single { get<MuvissDatabase>().profileQueries }
    single<ProfileRepository> { SqlDelightProfileRepository(get(), get()) }
    single<ProfileApi> { DefaultProfileApi(get()) }

    factory { ObserveProfileUseCase(get()) }
    factory { SetDisplayNameUseCase(get()) }
    factory { SetAvatarUseCase(get()) }
    factory { ProfileActions(get(), get()) }
    factory { ObserveProfileStatsUseCase(get(), get(), get()) }
}
