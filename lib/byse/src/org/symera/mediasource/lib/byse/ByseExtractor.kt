package org.symera.mediasource.lib.byse

import android.util.Base64
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.symera.mediasource.lib.playlistutils.PlaylistUtils
import org.symera.source.model.SStream
import org.symera.source.network.awaitSuccess
import org.symera.source.online.GET
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class ByseExtractor(
    private val client: OkHttpClient,
    private val headers: Headers = Headers.headersOf(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    suspend fun embedUrlFromUrl(url: String): String? = runCatching {
        val host = url.substringAfter("://").substringBefore("/")
        val code = url.substringAfter("/e/").substringBefore("/").substringBefore("?")
        if (host.isBlank() || code.isBlank()) return null
        val details = client.awaitSuccess(
            GET(
                "https://$host/api/videos/$code/embed/details",
                headers.newBuilder()
                    .add("Referer", url.substringBefore("?"))
                    .add("Accept", "application/json")
                    .build(),
            ),
        ).use { it.body.string() }
        details.substringAfter("embed_frame_url", "")
            .substringAfter(":", "")
            .substringAfter('"', "")
            .substringBefore('"')
            .takeIf { it.isNotBlank() }
    }.getOrNull()

    suspend fun streamsFromUrl(url: String, prefix: String = ""): List<SStream> = runCatching {
        val host = url.substringAfter("://").substringBefore("/")
        val code = url.substringAfter("/e/").substringBefore("/").substringBefore("?")
        if (host.isBlank() || code.isBlank()) return emptyList()

        val details = client.awaitSuccess(
            GET(
                "https://$host/api/videos/$code/embed/details",
                headers.newBuilder()
                    .add("Referer", url.substringBefore("?"))
                    .add("Accept", "application/json")
                    .build(),
            ),
        ).use { it.body.string() }

        val embedUrl = details.substringAfter("embed_frame_url", "")
            .substringAfter(":", "")
            .substringAfter('"', "")
            .substringBefore('"')
            .takeIf { it.isNotBlank() }
            ?: return emptyList()
        val embedHost = embedUrl.substringAfter("://").substringBefore("/")
        val playbackHeaders = headers.newBuilder()
            .add("Referer", embedUrl)
            .add("Accept", "*/*")
            .add("Accept-Language", "en-US,en;q=0.9")
            .add("Cache-Control", "no-cache")
            .add("Pragma", "no-cache")
            .add("X-Embed-Origin", host)
            .add("X-Embed-Parent", url)
            .add("X-Embed-Referer", "https://$host")
            .add("Origin", "https://$host")
            .add("Sec-Fetch-Dest", "empty")
            .add("Sec-Fetch-Mode", "cors")
            .add("Sec-Fetch-Site", "same-origin")
            .build()
        val playbackBody = runCatching {
            client.awaitSuccess(GET("https://$embedHost/api/videos/$code/embed/playback", playbackHeaders))
                .use { it.body.string() }
        }.getOrElse {
            client.awaitSuccess(GET("https://$host/api/videos/$code/", playbackHeaders))
                .use { it.body.string() }
        }
        val playback = json.parseToJsonElement(playbackBody).jsonObject["playback"]?.jsonObject
            ?: return emptyList()
        val plain = decrypt(playback) ?: return emptyList()
        plain["sources"]?.jsonArray.orEmpty().mapNotNull { source ->
            val sourceUrl = (source.jsonObject["url"] ?: source.jsonObject["file"])?.jsonPrimitive?.content
                ?.takeIf { it.startsWith("http") }
                ?: return@mapNotNull null
            playlistUtils.extractFromHls(sourceUrl, url) { quality -> "$prefix Byse: $quality" }
        }.flatten()
    }.onFailure { error ->
        Log.w("ByseExtractor", "failure host=${url.safeHost()} category=${error.javaClass.simpleName}")
    }.getOrDefault(emptyList())

    private fun decrypt(playback: JsonObject): JsonObject? = runCatching {
        val key = playback["key_parts"]?.jsonArray.orEmpty()
            .map { decodeUrlBase64(it.jsonPrimitive.content) }
            .fold(ByteArray(0)) { acc, bytes -> acc + bytes }
        val iv = decodeUrlBase64(playback["iv"]?.jsonPrimitive?.content ?: return null)
        val payload = decodeUrlBase64(playback["payload"]?.jsonPrimitive?.content ?: return null)
        if (payload.size <= GCM_TAG_BYTES) return null

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BYTES * 8, iv),
        )
        json.parseToJsonElement(
            cipher.doFinal(payload).toString(Charsets.UTF_8),
        ).jsonObject
    }.getOrNull()

    private fun decodeUrlBase64(value: String): ByteArray {
        val padded = value + "=".repeat((4 - value.length % 4) % 4)
        return Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP)
    }

    private companion object {
        const val GCM_TAG_BYTES = 16
    }

    private fun String.safeHost(): String = toHttpUrlOrNull()?.host ?: "unknown"
}
