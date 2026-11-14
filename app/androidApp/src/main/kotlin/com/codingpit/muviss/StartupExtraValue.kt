package com.codingpit.muviss

/**
 * The value an activity starts with for an extra carried by its launch intent (#805).
 *
 * A configuration change (rotation, theme change) or process death recreates the activity, and the
 * system hands it the same launch intent again. Reading the extra on every `onCreate` would re-arm a
 * tap the person already acted on, so a recreated activity takes [savedPending] instead: what was still
 * unconsumed when its state was saved, which is null once the consumer has cleared it.
 *
 * Generic so a flag (the Pick Session request, `true` or null) follows the same rule as a string id.
 */
internal fun <T : Any> startupExtraValue(isRecreated: Boolean, fromIntent: T?, savedPending: T?): T? = if (isRecreated) savedPending else fromIntent
