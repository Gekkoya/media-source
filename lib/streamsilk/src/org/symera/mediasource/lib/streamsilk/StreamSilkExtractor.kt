package org.symera.mediasource.lib.streamsilk

import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.symera.mediasource.core.bodyString
import org.symera.mediasource.core.commonEmptyHeaders
import org.symera.mediasource.core.parseAs
import org.symera.mediasource.lib.playlistutils.PlaylistUtils
import org.symera.source.model.SStream
import org.symera.source.model.SubtitleTrack
import org.symera.source.online.GET
import org.symera.source.online.asJsoup

class StreamSilkExtractor(private val client: OkHttpClient, private val headers: Headers = commonEmptyHeaders) {
    private val srcRegex = Regex("var urlPlay =\\s*\"(.*?m3u8.*?)\"")
    private val subsRegex = Regex("jsonUrl = `([^`]*)`")
    private val videoHeaders by lazy {
        headers.newBuilder().set("Referer", "$STREAM_SILK_URL/").set("Origin", STREAM_SILK_URL).build()
    }
    private val playlistUtils by lazy { PlaylistUtils(client, videoHeaders) }

    fun streamsFromUrl(url: String, prefix: String) = streamsFromUrl(url) { "${prefix}StreamSilk:$it" }

    fun streamsFromUrl(url: String, videoNameGen: (String) -> String = { quality -> "StreamSilk:$quality" }): List<SStream> {
        val document = client.newCall(GET(url)).execute().asJsoup()
        val scriptData = document.select("script").firstOrNull { it.html().contains("h,u,n,t,e,r") }?.data() ?: return emptyList()
        val deHunt = JsHunter(scriptData).dehunt() ?: return emptyList()
        val link = srcRegex.find(deHunt)?.groupValues?.get(1)?.trim() ?: return emptyList()

        val subs = buildList {
            val subUrl = subsRegex.find(deHunt)?.groupValues?.get(1)?.trim()
            if (!subUrl.isNullOrEmpty()) {
                runCatching {
                    client.newCall(GET(subUrl, videoHeaders)).execute().bodyString()
                        .parseAs<List<SubtitleDto>>()
                        .forEach { add(SubtitleTrack(it.file, it.label)) }
                }
            }
        }

        return playlistUtils.extractFromHls(link, videoNameGen = videoNameGen, subtitleList = subs)
    }

    fun videosFromUrl(url: String, prefix: String): List<SStream> = streamsFromUrl(url, prefix)

    fun videosFromUrl(url: String, videoNameGen: (String) -> String = { quality -> "StreamSilk:$quality" }): List<SStream> = streamsFromUrl(url, videoNameGen)

    @Serializable
    data class SubtitleDto(val file: String, val label: String)
}

private const val STREAM_SILK_URL = "https://streamsilk.com"
