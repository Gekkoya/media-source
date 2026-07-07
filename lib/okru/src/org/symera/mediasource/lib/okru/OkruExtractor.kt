package org.symera.mediasource.lib.okru

import okhttp3.Headers
import okhttp3.OkHttpClient
import org.symera.mediasource.core.commonEmptyHeaders
import org.symera.mediasource.core.useAsJsoup
import org.symera.mediasource.lib.playlistutils.PlaylistUtils
import org.symera.source.model.SStream
import org.symera.source.online.GET

class OkruExtractor(private val client: OkHttpClient, private val headers: Headers = commonEmptyHeaders) {
    private val playlistUtils by lazy { PlaylistUtils(client) }

    fun streamsFromUrl(url: String, prefix: String = "", fixQualities: Boolean = true): List<SStream> {
        val document = client.newCall(GET(url, headers)).execute().useAsJsoup()
        val videoString = document.selectFirst("div[data-options]")?.attr("data-options") ?: return emptyList()

        return when {
            "ondemandHls" in videoString -> {
                val playlistUrl = videoString.extractLink("ondemandHls")
                playlistUtils.extractFromHls(playlistUrl, videoNameGen = { "Okru:$it".addPrefix(prefix) })
            }
            "ondemandDash" in videoString -> {
                val playlistUrl = videoString.extractLink("ondemandDash")
                playlistUtils.extractFromDash(playlistUrl, videoNameGen = { "Okru:$it".addPrefix(prefix) })
            }
            else -> streamsFromJson(videoString, prefix, fixQualities)
        }
    }

    private fun String.addPrefix(prefix: String) = prefix.takeIf(String::isNotBlank)?.let { "$prefix $this" } ?: this

    private fun String.extractLink(attr: String) = substringAfter("$attr\\\":\\\"").substringBefore("\\\"").replace("\\\\u0026", "&")

    private fun streamsFromJson(videoString: String, prefix: String = "", fixQualities: Boolean = true): List<SStream> {
        val arrayData = videoString.substringAfter("\\\"videos\\\":[{\\\"name\\\":\\\"").substringBefore("]")

        return arrayData.split("{\\\"name\\\":\\\"").reversed().mapNotNull { data ->
            val videoUrl = data.extractLink("url")
            val quality = data.substringBefore("\\\"").let { if (fixQualities) fixQuality(it) else it }
            val streamTitle = "Okru:$quality".addPrefix(prefix)
            if (videoUrl.startsWith("https://")) {
                SStream(url = videoUrl, title = streamTitle, initialized = true)
            } else {
                null
            }
        }
    }

    private fun fixQuality(quality: String): String {
        val qualities = listOf(
            "ultra" to "2160p",
            "quad" to "1440p",
            "full" to "1080p",
            "hd" to "720p",
            "sd" to "480p",
            "low" to "360p",
            "lowest" to "240p",
            "mobile" to "144p",
        )
        return qualities.find { it.first == quality }?.second ?: quality
    }
}
