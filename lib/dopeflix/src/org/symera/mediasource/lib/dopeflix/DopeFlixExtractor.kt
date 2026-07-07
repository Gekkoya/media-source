package org.symera.mediasource.lib.dopeflix

import android.util.Log
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.symera.mediasource.core.bodyString
import org.symera.mediasource.core.parseAs
import org.symera.mediasource.lib.playlistutils.PlaylistUtils
import org.symera.source.model.SStream
import org.symera.source.model.SubtitleTrack
import org.symera.source.online.GET
import java.net.URLEncoder

class DopeFlixExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val megaCloudAPI: String,
) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun getStreamsFromUrl(url: String, name: String): List<SStream> {
        val videos = getVideoDto(url)
        if (videos.isEmpty()) return emptyList()
        val subtitles = videos.first().tracks
            ?.filter { it.kind == "captions" }
            ?.map { SubtitleTrack(it.file, it.label) }
            .orEmpty()
            .let(playlistUtils::fixSubtitles)

        return videos.flatMap { video ->
            playlistUtils.extractFromHls(
                video.m3u8,
                videoNameGen = { "$name - $it" },
                subtitleList = subtitles,
                referer = "https://${url.toHttpUrl().host}/",
            )
        }
    }

    fun getVideosFromUrl(url: String, name: String): List<SStream> = getStreamsFromUrl(url, name)

    private fun getVideoDto(url: String): List<VideoDto> {
        val id = url.substringAfter(SOURCES_SPLITTER, "").substringBefore("?", "").ifEmpty { error("Failed to extract ID from URL") }
        val host = runCatching { url.toHttpUrl().host }.getOrNull() ?: error("MegaCloud host is invalid: $url")
        val serverUrl = "https://$host"
        val megaCloudHeaders = headers.newBuilder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Referer", "$serverUrl/")
            .build()

        val responseNonce = client.newCall(GET(url, megaCloudHeaders)).execute().bodyString()
        val match1 = Regex("""\b[a-zA-Z0-9]{48}\b""").find(responseNonce)
        val match2 = Regex("""\b([a-zA-Z0-9]{16})\b.*?\b([a-zA-Z0-9]{16})\b.*?\b([a-zA-Z0-9]{16})\b""").find(responseNonce)
        val nonce = match1?.value ?: match2?.let { it.groupValues[1] + it.groupValues[2] + it.groupValues[3] }
            ?: error("Failed to extract nonce from response")

        val data = client.newCall(GET("$serverUrl$SOURCES_URL$id&_k=$nonce", megaCloudHeaders)).execute().bodyString().parseAs<SourceResponseDto>()
        val key by lazy { requestNewKey() }

        return data.sources.map { source ->
            val encoded = source.file
            val m3u8 = if (!data.encrypted || ".m3u8" in encoded) {
                encoded
            } else {
                val fullUrl = buildString {
                    append(megaCloudAPI)
                    append("?encrypted_data=").append(URLEncoder.encode(encoded, "UTF-8"))
                    append("&nonce=").append(URLEncoder.encode(nonce, "UTF-8"))
                    append("&secret=").append(URLEncoder.encode(key, "UTF-8"))
                }
                Regex("\"file\":\"(.*?)\"")
                    .find(client.newCall(GET(fullUrl)).execute().bodyString())
                    ?.groupValues?.getOrNull(1)
                    ?: error("Video URL not found in decrypted response")
            }
            VideoDto(m3u8, data.tracks)
        }
    }

    private fun requestNewKey(): String = client.newCall(GET("https://raw.githubusercontent.com/yogesh-hacker/MegacloudKeys/refs/heads/main/keys.json"))
        .execute()
        .use { response ->
            if (!response.isSuccessful) error("Failed to fetch keys.json")
            val key = response.body.string().parseAs<Map<String, String>>()["mega"] ?: error("Mega key not found in keys.json")
            Log.d("MegaCloudExtractor", "Using Mega Key: $key")
            key
        }

    @Serializable
    data class VideoDto(val m3u8: String = "", val tracks: List<TrackDto>? = null)

    @Serializable
    data class SourceResponseDto(val sources: List<SourceDto>, val encrypted: Boolean = true, val tracks: List<TrackDto>? = null)

    @Serializable
    data class SourceDto(val file: String, val type: String)

    @Serializable
    data class TrackDto(val file: String, val kind: String, val label: String = "")

    companion object {
        private const val SOURCES_URL = "/embed-1/v3/e-1/getSources?id="
        private const val SOURCES_SPLITTER = "/e-1/"
    }
}
