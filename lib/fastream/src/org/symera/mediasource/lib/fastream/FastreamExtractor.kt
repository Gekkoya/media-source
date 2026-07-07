package org.symera.mediasource.lib.fastream

import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.symera.mediasource.core.commonEmptyHeaders
import org.symera.mediasource.lib.playlistutils.PlaylistUtils
import org.symera.mediasource.lib.unpacker.JsUnpacker
import org.symera.source.model.SStream
import org.symera.source.online.GET
import org.symera.source.online.POST
import org.symera.source.online.asJsoup

class FastreamExtractor(private val client: OkHttpClient, private val headers: Headers = commonEmptyHeaders) {
    private val videoHeaders by lazy {
        headers.newBuilder()
            .set("Referer", "$FASTREAM_URL/")
            .set("Origin", FASTREAM_URL)
            .build()
    }

    private val playlistUtils by lazy { PlaylistUtils(client, videoHeaders) }

    fun streamsFromUrl(url: String, prefix: String = "Fastream:", needsSleep: Boolean = true): List<SStream> = runCatching {
        val firstDoc = client.newCall(GET(url, videoHeaders)).execute().asJsoup()
        if (needsSleep) Thread.sleep(5100L)

        val scriptElement = if (firstDoc.select("input[name]").any()) {
            val form = FormBody.Builder().apply {
                firstDoc.select("input[name]").forEach { add(it.attr("name"), it.attr("value")) }
            }.build()
            client.newCall(POST(url, videoHeaders, form)).execute().asJsoup()
                .selectFirst("script:containsData(jwplayer):containsData(vplayer)") ?: return@runCatching emptyList()
        } else {
            firstDoc.selectFirst("script:containsData(jwplayer):containsData(vplayer)") ?: return@runCatching emptyList()
        }

        val scriptData = scriptElement.data().let {
            if (it.contains("eval(function(")) JsUnpacker.unpackAndCombine(it) else it
        } ?: return@runCatching emptyList()
        val videoUrl = scriptData.substringAfter("file:\"").substringBefore("\"").trim()
        if (videoUrl.contains(".m3u8")) {
            playlistUtils.extractFromHls(videoUrl, videoNameGen = { "$prefix$it" })
        } else {
            listOf(SStream(url = videoUrl, title = prefix, headers = videoHeaders, initialized = true))
        }
    }.getOrDefault(emptyList())

    fun videosFromUrl(url: String, prefix: String = "Fastream:", needsSleep: Boolean = true): List<SStream> = streamsFromUrl(url, prefix, needsSleep)
}

private const val FASTREAM_URL = "https://fastream.to"
