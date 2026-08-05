package org.symera.mediasource.es.doramasflix

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.symera.source.HostAppInfo
import org.symera.source.SourceEnvironment
import org.symera.source.SourceLogger
import org.symera.source.SourcePreferenceStore
import org.symera.source.SourcePreferenceValues
import org.symera.source.model.ContentType

class DoramasflixCatalogResponseTest {
    private lateinit var source: Doramasflix

    @Before
    fun setUp() {
        source = Doramasflix(testEnvironment())
    }

    @Test
    fun `movie response maps item and page flag`() {
        val root = Json.parseToJsonElement(
            """
            {
              "data": {
                "paginationMovie": {
                  "pageInfo": {"hasNextPage": true},
                  "items": [{"name_es": "The Debt Collector"}]
                }
              }
            }
            """.trimIndent(),
        ).jsonObject

        val page = parseMoviePage(root)

        assertEquals("The Debt Collector", page.contents.single().title)
        assertEquals(ContentType.MOVIE, page.contents.single().contentType)
        assertTrue(page.hasNextPage)
    }

    @Test
    fun `series response maps item and page flag`() {
        val root = Json.parseToJsonElement(
            """
            {
              "data": {
                "paginationDorama": {
                  "pageInfo": {"hasNextPage": false},
                  "items": [{"name_es": "Goblin"}]
                }
              }
            }
            """.trimIndent(),
        ).jsonObject

        val page = parseSeriesPage(root)

        assertEquals("Goblin", page.contents.single().title)
        assertEquals(ContentType.SERIES, page.contents.single().contentType)
        assertFalse(page.hasNextPage)
    }

    @Test
    fun `moviesParse maps full graphql response`() {
        val page = invokeParser("moviesParse", response("""
            {
              "data": {
                "paginationMovie": {
                  "pageInfo": {"hasNextPage": true},
                  "items": [{"name_es": "The Debt Collector"}]
                }
              }
            }
        """))

        assertEquals("The Debt Collector", page.contents.single().title)
        assertEquals(ContentType.MOVIE, page.contents.single().contentType)
        assertTrue(page.hasNextPage)
    }

    @Test
    fun `seriesParse maps full graphql response`() {
        val page = invokeParser("seriesParse", response("""
            {
              "data": {
                "paginationDorama": {
                  "pageInfo": {"hasNextPage": false},
                  "items": [{"name_es": "Goblin"}]
                }
              }
            }
        """))

        assertEquals("Goblin", page.contents.single().title)
        assertEquals(ContentType.SERIES, page.contents.single().contentType)
        assertFalse(page.hasNextPage)
    }

    @Test
    fun `series response accepts null season id`() {
        val root = Json.parseToJsonElement(
            """
            {
              "data": {
                "paginationDorama": {
                  "pageInfo": {"hasNextPage": true},
                  "items": [{
                    "_id": "series-1",
                    "name": "My Mr. Ostrich",
                    "name_es": "Mi Sr. Avestruz",
                    "slug": "my-mr-ostrich",
                    "seasons": [{
                      "_id": null,
                      "slug": "my-mr-ostrich-1",
                      "season_number": 1,
                      "number_of_episodes": 20,
                      "ref": "season-1"
                    }]
                  }]
                }
              }
            }
            """.trimIndent(),
        ).jsonObject

        val page = parseSeriesPage(root)

        assertEquals("Mi Sr. Avestruz", page.contents.single().title)
        assertTrue(page.hasNextPage)
    }

    private fun response(body: String): Response = Response.Builder()
        .request(Request.Builder().url("https://doramasflix.test/catalog").build())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(body.trimIndent().toResponseBody())
        .build()

    private fun invokeParser(name: String, response: Response) = source.javaClass
        .getDeclaredMethod(name, Response::class.java)
        .apply { isAccessible = true }
        .invoke(source, response) as org.symera.source.model.ContentPage

    private fun testEnvironment() = object : SourceEnvironment {
        override val httpClient = OkHttpClient()
        override val userAgent = "DoramasflixCatalogResponseTest"
        override val appInfo = HostAppInfo(versionCode = 1, versionName = "test", sdkVersion = 35)
        override val logger = object : SourceLogger {
            override fun debug(message: String) = Unit
            override fun warning(message: String, cause: Throwable?) = Unit
            override fun error(message: String, cause: Throwable?) = Unit
        }

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
}
