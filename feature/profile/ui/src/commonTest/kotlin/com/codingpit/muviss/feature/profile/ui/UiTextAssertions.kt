package com.codingpit.muviss.feature.profile.ui

import com.codingpit.muviss.core.designsystem.text.UiText
import com.codingpit.muviss.core.designsystem.text.resolveAsync

/** State copy is [UiText] since EPIC 31; tests compare what an English-locale user reads. */
internal suspend fun UiText?.text(): String? = this?.resolveAsync()
