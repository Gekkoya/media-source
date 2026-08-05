package org.symera.mediasource.es.doramasflix

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class DoramasflixHosterTest {
    @Test
    fun `embed wins over link and mediafire is rejected`() {
        val hosters = DoramasflixHosterParser.parse(
            listOf(
                link(link = "https://mediafire.com/file/a", embed = "https://doodstream.com/e/abc"),
                link(link = "https://mediafire.com/file/b", server = "MediaFire"),
            ),
        )

        assertEquals(listOf("https://doodstream.com/e/abc"), hosters.map { it.url })
    }

    @Test
    fun `fkplayer link wins over unsupported embed mirror`() {
        val hosters = DoramasflixHosterParser.parse(
            listOf(
                link(
                    link = "https://fkplayer.xyz/e/token",
                    embed = "https://primeload.co/embed/abc",
                ),
            ),
        )

        assertEquals("https://fkplayer.xyz/e/token", hosters.single().url)
    }

    @Test
    fun `embedshortener token unwraps nested provider link`() {
        val token = "https://embedshortener.co/e/eyJhbGciOiJIUzI1NiJ9.eyJsaW5rIjoiYUhSMGNITTZMeTl3Y21sdFpXeHZZV1F1WTI4dlpXMWlaV1F2Y1U0NVZqUjZTWHBDWTBObyJ9.signature"
        assertEquals("https://primeload.co/embed/qN9V4zIzBcCh", DoramasflixHosterParser.effectiveUrl(token, null))
        val hosters = DoramasflixHosterParser.parse(
            listOf(
                link(
                    link = token,
                ),
            ),
        )

        assertEquals("https://primeload.co/embed/qN9V4zIzBcCh", hosters.single().url)
    }

    @Test
    fun `generic server label does not replace url host`() {
        val hoster = DoramasflixHosterParser.parse(
            listOf(link(link = "https://voe.example/e/abc", server = "1")),
        ).single()

        assertEquals("https://voe.example/e/abc", hoster.url)
        assertEquals("Voe", hoster.hostName)
    }

    @Test
    fun `blank and malformed entries are skipped`() {
        val hosters = DoramasflixHosterParser.parse(
            listOf(JsonPrimitive("bad"), link(link = ""), link(link = "https://doodstream.com/e/a")),
        )

        assertEquals(1, hosters.size)
    }

    @Test
    fun `duplicate effective urls are emitted once`() {
        val hosters = DoramasflixHosterParser.parse(
            listOf(
                link(link = "https://doodstream.com/e/a"),
                link(link = "https://doodstream.com/e/a", embed = "https://doodstream.com/e/a"),
            ),
        )

        assertEquals(1, hosters.size)
    }

    @Test
    fun `listProblems links use same parser`() {
        val hosters = DoramasflixHosterParser.parsePayload(
            linksOnline = emptyList(),
            listProblems = listOf(link(link = "https://voe.example/e/a")),
        )

        assertEquals(1, hosters.size)
    }

    @Test
    fun `nested server link uses same parser`() {
        val hosters = DoramasflixHosterParser.parse(
            listOf(buildJsonObject {
                put("server", link(link = "https://doodstream.com/e/a"))
            }),
        )

        assertEquals("https://doodstream.com/e/a", hosters.single().url)
    }

    private fun link(
        link: String,
        embed: String? = null,
        server: String? = null,
    ) = buildJsonObject {
        put("link", link)
        embed?.let { put("embed", it) }
        server?.let { put("server", it) }
    }
}
