package org.symera.mediasource.lib.streamlare

import okhttp3.OkHttpClient
import org.symera.mediasource.core.bodyString
import org.symera.mediasource.core.toJsonRequestBody
import org.symera.source.model.SStream
import org.symera.source.online.GET
import org.symera.source.online.POST

class StreamlareExtractor(private val client: OkHttpClient) {
    fun streamsFromUrl(url: String, prefix: String = "", suffix: String = ""): List<SStream> {
        val id = url.split("/").last()
        val playlist = client.newCall(
            POST(
                "https://slwatch.co/api/video/stream/get",
                body = "{\"id\":\"$id\"}".toJsonRequestBody(),
            ),
        ).execute().bodyString()

        val type = playlist.substringAfter("\"type\":\"").substringBefore("\"")
        return if (type == "hls") {
            val masterPlaylistUrl = playlist.substringAfter("\"file\":\"").substringBefore("\"").replace("\\/", "/")
            val masterPlaylist = client.newCall(GET(masterPlaylistUrl)).execute().bodyString()
            val separator = "#EXT-X-STREAM-INF"
            masterPlaylist.substringAfter(separator).split(separator).mapNotNull {
                val quality = it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore(",") + "p"
                val videoUrl = it.substringAfter("\n").substringBefore("\n").takeIf(String::isNotBlank)?.let { urlPart ->
                    if (urlPart.startsWith("http")) urlPart else masterPlaylistUrl.substringBefore("master.m3u8") + urlPart
                } ?: return@mapNotNull null
                SStream(url = videoUrl, title = buildQuality(quality, prefix, suffix), initialized = true)
            }
        } else {
            val separator = "\"label\":\""
            playlist.substringAfter(separator).split(separator).mapNotNull {
                val quality = it.substringBefore("\",")
                val apiUrl = it.substringAfter("\"file\":\"").substringBefore("\",").replace("\\", "")
                    .takeIf(String::isNotBlank) ?: return@mapNotNull null
                val videoUrl = client.newCall(POST(apiUrl, body = "".toJsonRequestBody())).execute().request.url.toString()
                SStream(url = videoUrl, title = buildQuality(quality, prefix, suffix), initialized = true)
            }
        }
    }

    fun videosFromUrl(url: String, prefix: String = "", suffix: String = ""): List<SStream> = streamsFromUrl(url, prefix, suffix)

    private fun buildQuality(resolution: String, prefix: String = "", suffix: String = "") = buildString {
        if (prefix.isNotBlank()) append("$prefix ")
        append("Streamlare:$resolution")
        if (suffix.isNotBlank()) append(" $suffix")
    }
}
