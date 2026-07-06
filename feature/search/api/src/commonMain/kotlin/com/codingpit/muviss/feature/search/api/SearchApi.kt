package com.codingpit.muviss.feature.search.api

/**
 * Public contract of the search feature. Nothing consumes it today (discovery
 * is a leaf feature), but it exists so peers could, e.g., trigger a search
 * without depending on search's internals.
 */
interface SearchApi
