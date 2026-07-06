package com.codingpit.muviss.feature.profile.data.di

import com.codingpit.muviss.feature.profile.api.ProfileApi
import com.codingpit.muviss.feature.profile.data.DefaultProfileApi
import org.koin.core.module.Module
import org.koin.dsl.module

val profileDataModule: Module = module {
    single<ProfileApi> { DefaultProfileApi() }
}
