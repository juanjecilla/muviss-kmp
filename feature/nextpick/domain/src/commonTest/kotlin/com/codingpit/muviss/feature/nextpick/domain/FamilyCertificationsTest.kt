package com.codingpit.muviss.feature.nextpick.domain

import com.codingpit.muviss.models.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals

class FamilyCertificationsTest {

    @Test
    fun the_us_has_separate_film_and_tv_ratings() {
        assertEquals(CertificationFilter("US", setOf("G", "PG")), FamilyCertifications.forRegion("US", MediaType.MOVIE))
        assertEquals(CertificationFilter("US", setOf("TV-Y", "TV-Y7", "TV-G", "TV-PG")), FamilyCertifications.forRegion("US", MediaType.TV))
    }

    @Test
    fun spain_has_its_own_table_and_the_region_is_case_insensitive() {
        assertEquals(CertificationFilter("ES", setOf("A", "Ai", "7", "7i")), FamilyCertifications.forRegion("es", MediaType.MOVIE))
        assertEquals(CertificationFilter("ES", setOf("ERI", "TP", "7")), FamilyCertifications.forRegion("ES", MediaType.TV))
    }

    @Test
    fun an_unknown_or_missing_region_falls_back_to_the_us() {
        assertEquals("US", FamilyCertifications.forRegion("JP", MediaType.MOVIE).country)
        assertEquals("US", FamilyCertifications.forRegion(null, MediaType.TV).country)
        assertEquals("US", FamilyCertifications.forRegion("", MediaType.MOVIE).country)
    }
}
