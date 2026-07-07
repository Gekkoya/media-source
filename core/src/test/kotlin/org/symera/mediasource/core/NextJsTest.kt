package org.symera.mediasource.core

import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.symera.mediasource.core.reactFlight.ReactFlightBigInt
import org.symera.mediasource.core.reactFlight.ReactFlightDate
import org.symera.mediasource.core.reactFlight.ReactFlightNumber

class NextJsTest {
    private fun fixture(name: String): String = javaClass.getResource("/reactflight/$name.txt")!!.readText()

    @Serializable
    data class FixtureMarkers(
        val name: String,
        val big: ReactFlightBigInt,
        val date: ReactFlightDate,
        val inf: ReactFlightNumber,
        val negInf: ReactFlightNumber,
        val nan: ReactFlightNumber,
        val negZero: ReactFlightNumber,
        val plain: ReactFlightNumber,
        val missing: String? = null,
    )

    @Test
    fun realFlightMarkersFixture() {
        val markers = fixture("markers").extractNextJsRsc<FixtureMarkers>()
        assertNotNull(markers)
        markers!!
        assertEquals("hello", markers.name)
        assertEquals("123456789012345678901234567890", markers.big.toString())
        assertEquals(1704164645000L, markers.date.time)
        assertTrue(markers.inf == Double.POSITIVE_INFINITY)
        assertTrue(markers.negInf == Double.NEGATIVE_INFINITY)
        assertTrue(markers.nan.isNaN())
        assertEquals(-0.0, markers.negZero, 0.0)
        assertEquals(3.14, markers.plain, 0.0)
        assertEquals(null, markers.missing)
    }

    @Serializable
    data class LargeText(val title: String, val body: String, val unicode: String)

    @Test
    fun realFlightLargeTextFixture() {
        val expectedBody = "Lorem ipsum dolor sit amet. ".repeat(60)
        val expectedUnicode = "hello world hola mundo rocket ".repeat(80)
        val text = fixture("largetext").extractNextJsRsc<LargeText>()
        assertNotNull(text)
        text!!
        assertEquals("big", text.title)
        assertEquals(expectedBody, text.body)
        assertEquals(expectedUnicode, text.unicode)
    }

    @Serializable
    data class Collections(val items: Map<String, Int>, val tags: List<Int>)

    @Test
    fun realFlightCollectionsFixture() {
        val collections = fixture("collections").extractNextJsRsc<Collections>()
        assertNotNull(collections)
        assertEquals(mapOf("a" to 1, "b" to 2), collections!!.items)
        assertEquals(listOf(10, 20, 30), collections.tags)
    }
}
