package com.codingpit.muviss.feature.nextpick.data

import com.codingpit.muviss.feature.nextpick.domain.PickMuting
import com.codingpit.muviss.feature.settings.api.SettingsApi
import com.codingpit.muviss.models.MutedGenres
import kotlinx.coroutines.flow.first

/**
 * The muting a Next Pick applies: Muted Genres as Settings reports them now, which already folds in the
 * session's "show muted" chip (ADR 0031). The engine, not this adapter, lifts a mute for a mood the person
 * picked.
 */
internal class SettingsMuting(private val settings: () -> SettingsApi) : PickMuting {
    override suspend fun current(): MutedGenres = settings().observeMuting().first().effective
}
