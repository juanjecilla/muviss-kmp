package com.codingpit.muviss.feature.settings.domain

import com.codingpit.muviss.models.GenreKey
import com.codingpit.muviss.models.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The effective Muted Genres rule (ADR 0036). Commonly named in underscore_case: Kotlin/Native rejects the
 * punctuation a backticked sentence needs, and this suite runs on every target.
 */
class MutedGenresRuleTest {

    private val talk = MutedGenreEntry(GenreKey(MediaType.TV, "10767"), "Talk")
    private val news = MutedGenreEntry(GenreKey(MediaType.TV, "10763"), "News")
    private val reality = MutedGenreEntry(GenreKey(MediaType.TV, "10764"), "Reality")
    private val horror = GenreKey(MediaType.MOVIE, "27")

    private val defaults = listOf(talk, news, reality)

    private fun decision(key: GenreKey, name: String, deleted: Boolean) = MutedGenreDecision(key, name, deleted)

    @Test
    fun a_device_that_never_chose_mutes_the_code_defaults() {
        assertEquals(defaults, MutedGenresRule.effective(defaults, emptyList()))
    }

    @Test
    fun a_tombstone_unmutes_a_default_and_leaves_the_others_muted() {
        val effective = MutedGenresRule.effective(defaults, listOf(decision(talk.key, "Talk", deleted = true)))

        assertEquals(listOf(news, reality), effective)
    }

    @Test
    fun a_live_decision_mutes_a_genre_that_was_not_a_default() {
        val effective = MutedGenresRule.effective(defaults, listOf(decision(horror, "Horror", deleted = false)))

        assertEquals(defaults + MutedGenreEntry(horror, "Horror"), effective)
    }

    @Test
    fun a_chosen_device_follows_the_snapshot_it_recorded_not_the_code_defaults_of_today() {
        // The marker stores the defaults in force at the choice; the caller passes that snapshot, so a
        // default added since does not reach the device.
        val snapshotAtChoice = defaults
        val codeDefaultsToday = defaults + MutedGenreEntry(horror, "Horror")

        assertEquals(defaults, MutedGenresRule.effective(snapshotAtChoice, emptyList()))
        assertEquals(codeDefaultsToday, MutedGenresRule.effective(codeDefaultsToday, emptyList()))
    }

    @Test
    fun a_decision_names_the_genre_the_person_saw() {
        val effective = MutedGenresRule.effective(defaults, listOf(decision(talk.key, "Talk Show", deleted = false)))

        assertEquals("Talk Show", effective.single { it.key == talk.key }.name)
    }

    @Test
    fun mute_nothing_is_a_marker_with_tombstones_for_every_default() {
        val tombstones = defaults.map { decision(it.key, it.name, deleted = true) }

        assertEquals(emptyList(), MutedGenresRule.effective(defaults, tombstones))
    }

    @Test
    fun a_tombstone_for_a_genre_that_was_never_muted_mutes_nothing() {
        val effective = MutedGenresRule.effective(defaults, listOf(decision(horror, "Horror", deleted = true)))

        assertEquals(defaults, effective)
    }
}
