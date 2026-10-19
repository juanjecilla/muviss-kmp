package com.codingpit.muviss.feature.settings.domain

/**
 * What the Settings screen observes, grouped to keep its view model's constructor short
 * (the same reason [SettingsActions] groups the mutators).
 */
class SettingsObservations(
    val settings: ObserveSettingsUseCase,
    val featureRequestLink: ObserveFeatureRequestLinkUseCase,
)
