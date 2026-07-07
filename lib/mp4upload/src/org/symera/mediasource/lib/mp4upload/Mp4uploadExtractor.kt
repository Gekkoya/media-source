package org.symera.mediasource.lib.mp4upload

import okhttp3.Headers
import okhttp3.OkHttpClient
import org.symera.mediasource.lib.unpacker.JsUnpacker
import org.symera.source.model.SStream
import org.symera.source.online.GET
import org.symera.source.online.asJsoup

class Mp4uploadExtractor(private val client: OkHttpClient) {
    fun streamsFromUrl(url: String, headers: Headers, prefix: String = "", suffix: String = ""): List<SStream> {
        val newHeaders = headers.newBuilder().set("Referer", REFERER).build()
        val doc = client.newCall(GET(url, newHeaders)).execute().asJsoup()

        val script = doc.selectFirst("script:containsData(eval):containsData(p,a,c,k,e,d)")?.data()
            ?.let(JsUnpacker::unpackAndCombine)
            ?: doc.selectFirst("script:containsData(player.src)")?.data()
            ?: return emptyList()

        val videoUrl = script.substringAfter(".src(").substringBefore(")")
            .substringAfter("src:").substringAfter('"').substringBefore('"')
        val resolution = QUALITY_REGEX.find(script)?.groupValues?.let { "${it[1]}p" } ?: "Unknown resolution"
        val quality = "${prefix}Mp4Upload - $resolution$suffix"

        return listOf(
            SStream(
                url = videoUrl,
                title = quality,
                resolution = QUALITY_REGEX.find(script)?.groupValues?.getOrNull(1)?.toIntOrNull(),
                headers = newHeaders,
                initialized = true,
            ),
        )
    }

    companion object {
        private val QUALITY_REGEX by lazy { """\WHEIGHT=(\d+)""".toRegex() }
        private const val REFERER = "https://mp4upload.com/"
    }
}
