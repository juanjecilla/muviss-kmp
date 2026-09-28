package com.codingpit.muviss.core.common.locale

import java.util.Locale

actual class DefaultSystemLocale actual constructor() : SystemLocale {
    actual override val languageTag: String?
        get() = Locale.getDefault().toLanguageTag()
}
