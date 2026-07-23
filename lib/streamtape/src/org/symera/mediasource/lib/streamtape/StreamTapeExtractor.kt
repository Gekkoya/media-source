package org.symera.mediasource.lib.streamtape

import android.util.Log
import okhttp3.OkHttpClient
import org.symera.source.model.HeaderScope
import org.symera.source.model.HttpHeader
import org.symera.source.model.MediaRequest
import org.symera.source.model.PlayableStream
import org.symera.source.model.SStream
import org.symera.source.model.StreamProtocol
import org.symera.source.model.SubtitleTrack
import org.symera.source.online.GET
import org.symera.source.online.asJsoup

class StreamTapeExtractor(private val client: OkHttpClient) {
    fun streamFromUrl(url: String, quality: String = "Streamtape", subtitleList: List<SubtitleTrack> = emptyList()): SStream? {
        val baseUrl = "https://streamtape.com/e/"
        val newUrl = if (!url.startsWith(baseUrl)) {
            val id = url.split("/").getOrNull(4) ?: return null
            baseUrl + id
        } else {
            url
        }

        val document = client.newCall(GET(newUrl)).execute().asJsoup()
        val targetLine = "document.getElementById('robotlink')"
        val script = document.selectFirst("script:containsData($targetLine)")
            ?.data()
            ?.substringAfter("$targetLine.innerHTML = '")
            ?: return null
        val videoUrl = "https:" + script.substringBefore("'") + script.substringAfter("+ ('xcd").substringBefore("'")
        runCatching {
            val parsed = java.net.URI(videoUrl)
            Log.d("StreamTapeExtractor", "resolved host=${parsed.host} path=${parsed.path} queryLength=${parsed.query?.length ?: 0}")
        }

        return PlayableStream(
            id = videoUrl,
            title = quality,
            protocol = StreamProtocol.PROGRESSIVE,
            request = MediaRequest(
                uri = videoUrl,
                headers = listOf(
                    HttpHeader("Referer", newUrl),
                    HttpHeader("Origin", "https://streamtape.com"),
                    HttpHeader("User-Agent", USER_AGENT),
                ),
                headerScope = HeaderScope.ALL_DERIVED_REQUESTS,
            ),
            subtitleTracks = subtitleList,
        )
    }

    fun streamsFromUrl(url: String, quality: String = "Streamtape", subtitleList: List<SubtitleTrack> = emptyList()): List<SStream> = streamFromUrl(url, quality, subtitleList)?.let(::listOf).orEmpty()
}

private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"
