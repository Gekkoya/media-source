package org.symera.mediasource.lib.okru

import okhttp3.Headers
import okhttp3.OkHttpClient
import org.symera.mediasource.core.commonEmptyHeaders
import org.symera.mediasource.core.useAsJsoup
import org.symera.mediasource.lib.playlistutils.PlaylistUtils
import org.symera.source.model.MediaRequest
import org.symera.source.model.PlayableStream
import org.symera.source.model.SStream
import org.symera.source.online.GET

class OkruExtractor(private val client: OkHttpClient, private val headers: Headers = commonEmptyHeaders) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun streamsFromUrl(url: String, prefix: String = "", fixQualities: Boolean = true): List<SStream> {
        val sourceUrl = url.toHttpsUrl()
        val document = client.newCall(GET(sourceUrl, headers)).execute().useAsJsoup()
        val videoString = document.selectFirst("div[data-options]")?.attr("data-options") ?: return emptyList()

        return when {
            "ondemandHls" in videoString -> {
                val playlistUrl = videoString.extractLink("ondemandHls").toHttpsUrl()
                playlistUtils.extractFromHls(
                    playlistUrl,
                    referer = sourceUrl,
                    masterHeaders = headers.withReferer(sourceUrl),
                    videoHeaders = headers.withReferer(sourceUrl),
                    videoNameGen = { "Okru:$it".addPrefix(prefix) },
                )
            }
            "ondemandDash" in videoString -> {
                val playlistUrl = videoString.extractLink("ondemandDash").toHttpsUrl()
                playlistUtils.extractFromDash(
                    playlistUrl,
                    videoNameGen = { "Okru:$it".addPrefix(prefix) },
                    mpdHeaders = headers.withReferer(url),
                    videoHeaders = headers.withReferer(url),
                    referer = sourceUrl,
                )
            }
            else -> streamsFromJson(videoString, prefix, fixQualities)
        }
    }

    private fun String.addPrefix(prefix: String) = prefix.takeIf(String::isNotBlank)?.let { "$prefix $this" } ?: this

    private fun Headers.withReferer(url: String): Headers = newBuilder()
        .set("Referer", url)
        .set("User-Agent", USER_AGENT)
        .build()

    private fun String.extractLink(attr: String) = substringAfter("$attr\\\":\\\"").substringBefore("\\\"").replace("\\\\u0026", "&")

    private fun String.toHttpsUrl(): String = replaceFirst("^http://".toRegex(), "https://")

    private fun streamsFromJson(videoString: String, prefix: String = "", fixQualities: Boolean = true): List<SStream> {
        val normalized = videoString.replace("\\\"", "\"").replace("\\u0026", "&")
        val videoRegex = Regex("""[\"']name[\"']\s*:\s*[\"']([^\"']+)[\"']\s*,\s*[\"']url[\"']\s*:\s*[\"']([^\"']+)[\"']""")

        return videoRegex.findAll(normalized).mapNotNull { match ->
            val quality = match.groupValues[1].let { if (fixQualities) fixQuality(it) else it }
            val streamTitle = "Okru:$quality".addPrefix(prefix)
            val videoUrl = match.groupValues[2]
            if (videoUrl.startsWith("https://")) {
                PlayableStream(id = videoUrl, title = streamTitle, request = MediaRequest(uri = videoUrl))
            } else {
                null
            }
        }.toList()
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

private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"
