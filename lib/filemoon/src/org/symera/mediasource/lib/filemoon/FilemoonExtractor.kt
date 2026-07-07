package org.symera.mediasource.lib.filemoon

import android.util.Base64
import android.util.Log
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.symera.mediasource.core.bodyString
import org.symera.mediasource.core.parseAs
import org.symera.mediasource.lib.playlistutils.PlaylistUtils
import org.symera.source.model.SStream
import org.symera.source.online.GET
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class FilemoonExtractor(private val client: OkHttpClient) {
    private val playlistUtils by lazy { PlaylistUtils(client) }

    fun streamsFromUrl(url: String, prefix: String = "Filemoon - ", headers: Headers? = null): List<SStream> {
        return try {
            val httpUrl = url.toHttpUrl()
            val host = httpUrl.host
            val mediaId = if (httpUrl.pathSegments.size > 1 && httpUrl.pathSegments[0] == "e") {
                httpUrl.pathSegments[1]
            } else {
                httpUrl.pathSegments.lastOrNull { it.isNotEmpty() } ?: return emptyList()
            }

            val userAgent = headers?.get("User-Agent")
                ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"

            val embedUrl = client.newCall(GET("https://$host/api/videos/$mediaId/embed/details"))
                .execute().bodyString()
                .substringAfter("embed_frame_url", "")
                .substringAfter(":")
                .substringAfter('"')
                .substringBefore('"')

            if (embedUrl.isBlank()) return emptyList()

            val embedHost = embedUrl.toHttpUrl().host
            val playbackHeaders = (headers?.newBuilder() ?: Headers.Builder()).apply {
                set("Referer", embedUrl)
                set("X-Embed-Origin", host)
                set("X-Embed-Parent", url.encodeUrlPath())
                set("X-Embed-Referer", url)
                set("Accept", "*/*")
                set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
                set("Cache-Control", "no-cache")
                set("Pragma", "no-cache")
                set("Priority", "u=1, i")
                set("Sec-Fetch-Dest", "empty")
                set("Sec-Fetch-Mode", "cors")
                set("Sec-Fetch-Site", "same-origin")
                set("Sec-Fetch-Storage-Access", "active")
                set("User-Agent", userAgent)
            }.build()

            val apiUrl = "https://$embedHost/api/videos/$mediaId/embed/playback"
            val playbackJson = client.newCall(GET(apiUrl, playbackHeaders)).execute().parseAs<PlaybackResponse>()

            val finalSources = when {
                !playbackJson.sources.isNullOrEmpty() -> playbackJson.sources
                playbackJson.playback != null -> decrypt(playbackJson.playback).parseAs<PlaybackResponse>().sources
                else -> null
            }

            if (finalSources.isNullOrEmpty()) return emptyList()

            val videoHeaders = (headers?.newBuilder() ?: Headers.Builder()).apply {
                set("Referer", "https://$host/")
                set("User-Agent", userAgent)
                removeAll("Origin")
            }.build()

            finalSources.flatMap { source ->
                val streamUrl = source.url ?: source.file ?: return@flatMap emptyList<SStream>()
                val quality = source.label ?: "Unknown"

                playlistUtils.extractFromHls(
                    streamUrl,
                    masterHeaders = videoHeaders,
                    videoHeaders = videoHeaders,
                    videoNameGen = { "$prefix${it.replace("Video", quality)}p" },
                )
            }
        } catch (e: Exception) {
            Log.e("FilemoonExtractor", "Failed to extract video from $url", e)
            emptyList()
        }
    }

    private fun decrypt(input: PlaybackData): String {
        val keyBytes = input.key_parts.map { decodeBase64Url(it) }.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
        val ivBytes = decodeBase64Url(input.iv)
        val payloadBytes = decodeBase64Url(input.payload)

        val secretKey = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(128, ivBytes)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

        val decryptedBytes = cipher.doFinal(payloadBytes)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    private fun decodeBase64Url(input: String): ByteArray {
        val base64 = input.replace('-', '+').replace('_', '/')
        val padding = when (base64.length % 4) {
            2 -> "=="
            3 -> "="
            else -> ""
        }
        return Base64.decode(base64 + padding, Base64.DEFAULT)
    }

    @Serializable
    data class PlaybackResponse(
        val sources: List<VideoSource>? = null,
        val playback: PlaybackData? = null,
    )

    @Serializable
    data class PlaybackData(
        val iv: String,
        val key_parts: List<String>,
        val payload: String,
    )

    @Serializable
    data class VideoSource(
        val file: String? = null,
        val url: String? = null,
        val label: String? = "Default",
    )
}

fun String.encodeUrlPath(): String {
    val uri = URI(this)
    val encodedPath = uri.rawPath.split("/").joinToString("/") { segment ->
        if (segment.isEmpty()) "" else URLEncoder.encode(segment, StandardCharsets.UTF_8.toString()).replace("+", "%20")
    }
    return URI(uri.scheme, uri.rawAuthority, encodedPath, uri.rawQuery, uri.rawFragment).toString()
}
