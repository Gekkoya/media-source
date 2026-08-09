package org.symera.mediasource.es.jkanime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.symera.mediasource.es.jkanime.extractors.JkanimeExtractor
import org.symera.source.HostAppInfo
import org.symera.source.SourceEnvironment
import org.symera.source.SourceLogger
import org.symera.source.SourcePreferenceStore
import org.symera.source.SourcePreferenceValues
import org.symera.source.model.PlayableStream
import org.symera.source.model.SHoster

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

    @Test
    fun `migrated extractor ID depends on stable page and server identity`() {
        assertEquals(
            "jkanime-7f7f308ac346362dc8f70bcc35edead42eb69a0297b6616f5f30ed8b4585910d",
            JkanimeExtractor.streamId("episode-42", "nozomi"),
        )
    }

    @Test
    fun `hoster keys survive same server reorder`() {
        val original = hosterIdsByName(listOf("Voe", "Magi"))
        val reordered = hosterIdsByName(listOf("Magi", "Voe"))

        assertEquals(original, reordered)
    }

    @Test
    fun `Voe stream IDs keep lane identity across temporary URL rotation`() {
        val hoster =
            SHoster(
                id = JkanimeExtractor.streamId("/episode-42", "hoster:voe|[JAP]|Voe"),
                name = "[JAP] Voe",
                requestUrl = "https://voe.example/e/stable-page",
                resolverData = "[JAP]\tVoe\tvoe",
            )
        val first =
            Jkanime.stableVoeStreams(
                hoster,
                listOf(
                    PlayableStream(
                        id = "https://cdn.example/video.mp4?token=first-secret",
                        title = "[JAP] Voe:MP4",
                        request = org.symera.source.model.MediaRequest("https://cdn.example/video.mp4?token=first-secret"),
                    ),
                ),
            ).single() as PlayableStream
        val rotated =
            Jkanime.stableVoeStreams(
                hoster,
                listOf(
                    PlayableStream(
                        id = "https://cdn.example/video.mp4?token=second-secret",
                        title = "[JAP] Voe:MP4",
                        request = org.symera.source.model.MediaRequest("https://cdn.example/video.mp4?token=second-secret"),
                    ),
                ),
            ).single() as PlayableStream
        val otherLane =
            Jkanime.stableVoeStreams(
                hoster,
                listOf(
                    PlayableStream(
                        id = "https://cdn.example/video.m3u8?token=third-secret",
                        title = "[JAP] Voe:720p",
                        request = org.symera.source.model.MediaRequest("https://cdn.example/video.m3u8?token=third-secret"),
                    ),
                ),
            ).single() as PlayableStream

        assertEquals(first.id, rotated.id)
        assertNotEquals(first.id, otherLane.id)
        assertFalse(first.id.contains("first-secret"))
        assertFalse(rotated.id.contains("second-secret"))
        assertEquals("https://cdn.example/video.mp4?token=second-secret", rotated.request.uri)
    }

    @Test
    fun `episode decode failure logs category without throwable`() {
        val logger = RecordingLogger()
        val source =
            Jkanime(
                testEnvironment(
                    httpClient =
                    OkHttpClient.Builder()
                        .addInterceptor { chain ->
                            Response.Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(500)
                                .message("Server Error")
                                .body("not-json".toResponseBody())
                                .build()
                        }.build(),
                    logger = logger,
                ),
            )

        try {
            source.javaClass
                .getDeclaredMethod("fetchAnimeEpisodes", String::class.java, Int::class.javaPrimitiveType, okhttp3.FormBody::class.java)
                .apply { isAccessible = true }
                .invoke(source, "anime-42", 1, okhttp3.FormBody.Builder().build())
        } catch (_: java.lang.reflect.InvocationTargetException) {
        }

        assertEquals("Jkanime episode page unavailable: decode_error", logger.message)
        assertNull(logger.cause)
    }

    private fun hosterIdsByName(serverNames: List<String>): Map<String, String> = parseHosters(Jkanime(testEnvironment()), response(serverNames))
        .associate { hoster -> hoster.name to hoster.id }

    @Suppress("UNCHECKED_CAST")
    private fun parseHosters(source: Jkanime, response: Response): List<SHoster> = source.javaClass
        .getDeclaredMethod("hostersParse", Response::class.java)
        .apply { isAccessible = true }
        .invoke(source, response) as List<SHoster>

    private fun response(serverNames: List<String>): Response {
        val servers = serverNames.mapIndexed { index, name ->
            val id = index + 1
            "video[$id] = '<iframe class=\"player_conte\" src=\"https://jkanime.net/jkplayer/${name.lowercase()}\">'"
        }
        val links =
            serverNames.mapIndexed { index, name ->
                val id = index + 1
                "<a data-id=\"$id\" class=\"lg_1\">$name</a>"
            }
        return Response.Builder()
            .request(Request.Builder().url("https://jkanime.net/episode-42").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("<script>var video = []; ${servers.joinToString(";")}</script><div class=\"bg-servers\">${links.joinToString()}</div>".toResponseBody())
            .build()
    }

    private fun testEnvironment(
        httpClient: OkHttpClient = OkHttpClient(),
        logger: SourceLogger = object : SourceLogger {
            override fun debug(message: String) = Unit

            override fun warning(message: String, cause: Throwable?) = Unit

            override fun error(message: String, cause: Throwable?) = Unit
        },
    ) = object : SourceEnvironment {
        override val httpClient = httpClient
        override val userAgent = "JkanimeRoutingTest"
        override val appInfo = HostAppInfo(versionCode = 1, versionName = "test", sdkVersion = 35)
        override val logger = logger

        override fun preferencesFor(namespace: String) = SourcePreferenceValues(
            object : SourcePreferenceStore {
                override fun getString(key: String, default: String) = default

                override fun getSecret(key: String, default: String) = default

                override fun getBoolean(key: String, default: Boolean) = default

                override fun getLong(key: String, default: Long) = default

                override fun getStringSet(key: String, default: Set<String>) = default

                override suspend fun putString(key: String, value: String) = Unit

                override suspend fun putSecret(key: String, value: String) = Unit

                override suspend fun putBoolean(key: String, value: Boolean) = Unit

                override suspend fun putLong(key: String, value: Long) = Unit

                override suspend fun putStringSet(key: String, value: Set<String>) = Unit

                override suspend fun remove(key: String) = Unit

                override fun observeChanges(): Flow<String> = emptyFlow()
            },
        )
    }

    private class RecordingLogger : SourceLogger {
        var message: String? = null
        var cause: Throwable? = null

        override fun debug(message: String) = Unit

        override fun warning(message: String, cause: Throwable?) = Unit

        override fun error(message: String, cause: Throwable?) {
            this.message = message
            this.cause = cause
        }
    }
}
