package com.codingpit.muviss.core.common.platform

/**
 * Whether this platform's main input is touch, so a pull from the top of a list is a gesture people make (#965).
 * Android and iOS do. Desktop and web are driven by a pointer and a keyboard, where the refresh control and
 * Ctrl/Cmd+R are the way in, and a drag-down is not something anyone reaches for.
 */
expect val platformHasPullToRefresh: Boolean
