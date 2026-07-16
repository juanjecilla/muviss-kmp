package com.codingpit.muviss.feature.collection.ui

import kotlinx.serialization.Serializable

/**
 * Destination for one list's contents (EPIC 17), pushed from the Collection
 * screen's Lists segment. [name] rides along so the top bar has a title
 * without an extra lookup query — [listId] alone drives the actual content
 * query (see `ListContentsViewModel`).
 */
@Serializable
data class ListContentsRoute(val listId: String, val name: String)
