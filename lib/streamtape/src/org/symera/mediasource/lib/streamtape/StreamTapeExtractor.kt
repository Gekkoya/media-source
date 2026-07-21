package org.symera.mediasource.lib.streamtape

import okhttp3.OkHttpClient
import org.symera.source.model.MediaRequest
import org.symera.source.model.PlayableStream
import org.symera.source.model.SStream
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

        return PlayableStream(id = videoUrl, title = quality, request = MediaRequest(uri = videoUrl), subtitleTracks = subtitleList)
    }

    fun streamsFromUrl(url: String, quality: String = "Streamtape", subtitleList: List<SubtitleTrack> = emptyList()): List<SStream> = streamFromUrl(url, quality, subtitleList)?.let(::listOf).orEmpty()
}
