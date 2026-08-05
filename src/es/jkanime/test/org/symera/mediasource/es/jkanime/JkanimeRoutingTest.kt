package org.symera.mediasource.es.jkanime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class JkanimeRoutingTest {
    @Test
    fun `jkplayer um routes to desu`() {
        assertEquals("desu", JkanimeRouting.match("https://jkanime.net/jkplayer/um", "fallback"))
    }

    @Test
    fun `jkplayer umv routes to magi without desu collision`() {
        val route = JkanimeRouting.match("https://jkanime.net/jkplayer/umv", "fallback")

        assertEquals("magi", route)
        assertNotEquals("desu", route)
    }

    @Test
    fun `byse aliases route to byse`() {
        assertEquals("byse", JkanimeRouting.match("https://bysekoze.com/e/abc/", "fallback"))
        assertEquals("byse", JkanimeRouting.match("https://bysefujedu.com/e/abc/", "fallback"))
    }

    @Test
    fun `unrelated hosts and paths do not collide with aliases`() {
        assertEquals("fallback", JkanimeRouting.match("https://notvoe.example/player", "fallback"))
        assertEquals("fallback", JkanimeRouting.match("https://notwish.example/player", "fallback"))
        assertEquals("fallback", JkanimeRouting.match("https://voe.evil/player", "fallback"))
        assertEquals("fallback", JkanimeRouting.match("https://bysekoze.evil/e/abc/", "fallback"))
        assertEquals("fallback", JkanimeRouting.match("https://jkanime.net/jkplayer/umbrella", "fallback"))
    }

    @Test
    fun `hostname alias accepts proper subdomain boundary`() {
        assertEquals("voe", JkanimeRouting.match("https://cdn.voe/player", "fallback"))
    }
}
