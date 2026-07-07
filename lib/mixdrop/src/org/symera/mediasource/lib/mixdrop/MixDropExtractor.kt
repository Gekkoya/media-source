package org.symera.mediasource.lib.mixdrop

import okhttp3.Headers
import okhttp3.OkHttpClient
import org.symera.mediasource.lib.unpacker.Unpacker
import org.symera.source.model.SStream
import org.symera.source.model.SubtitleTrack
import org.symera.source.online.GET
import org.symera.source.online.asJsoup
import java.net.URLDecoder

class MixDropExtractor(private val client: OkHttpClient) {
    fun streamsFromUrl(
        url: String,
        lang: String = "",
        prefix: String = "",
        externalSubs: List<SubtitleTrack> = emptyList(),
        referer: String = DEFAULT_REFERER,
    ): List<SStream> {
        val headers = Headers.headersOf(
            "Referer",
            referer,
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36",
        )
        val doc = client.newCall(GET(url, headers)).execute().asJsoup()
        val unpacked = doc.selectFirst("script:containsData(eval):containsData(MDCore)")
            ?.data()
            ?.let(Unpacker::unpack)
            ?: return emptyList()

        val videoUrl = "https:" + unpacked.substringAfter("Core.wurl=\"").substringBefore("\"")
        val subs = unpacked.substringAfter("Core.remotesub=\"").substringBefore('"')
            .takeIf(String::isNotBlank)
            ?.let { listOf(SubtitleTrack(URLDecoder.decode(it, "utf-8"), "sub")) }
            ?: emptyList()

        val quality = buildString {
            append("${prefix}MixDrop")
            if (lang.isNotBlank()) append("($lang)")
        }

        return listOf(
            SStream(
                url = videoUrl,
                title = quality,
                headers = headers,
                subtitleTracks = subs + externalSubs,
                initialized = true,
            ),
        )
    }
}

private const val DEFAULT_REFERER = "https://mixdrop.co/"
