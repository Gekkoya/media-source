package org.symera.mediasource.lib.streamsb

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.symera.mediasource.core.toJsonBody
import org.symera.mediasource.lib.playlistutils.PlaylistUtils
import org.symera.source.model.SStream
import org.symera.source.network.awaitSuccess
import org.symera.source.online.POST

class StreamSbExtractor(
    private val client: OkHttpClient,
    private val headers: Headers = Headers.headersOf(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    suspend fun streamsFromUrl(url: String, prefix: String = ""): List<SStream> = runCatching {
        val host = url.toHttpUrl().host
        val id = url.substringAfterLast("/e/").substringBefore(".").substringBefore("?")
        if (id.isBlank()) return emptyList()
        val body = buildJsonObject {
            put("r", JsonPrimitive(""))
            put("d", JsonPrimitive(host))
        }.toString().toJsonBody()
        val response = client.awaitSuccess(
            POST(
                "https://$host/api/source/$id",
                headers.newBuilder()
                    .add("Referer", "https://$host/")
                    .add("Content-Type", "application/json")
                    .build(),
                body,
            ),
        ).use { it.body.string() }
        val root = json.parseToJsonElement(response).jsonObject
        val sources = root["stream"]?.jsonArray ?: root["data"]?.jsonArray ?: return emptyList()
        sources.mapNotNull { source ->
            val sourceObject = source.jsonObject
            val file = sourceObject["file"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val label = sourceObject["label"]?.jsonPrimitive?.contentOrNull ?: "Video"
            playlistUtils.extractFromHls(file, url) { quality -> "$prefix StreamSB: $label $quality" }
        }.flatten()
    }.getOrDefault(emptyList())
}
