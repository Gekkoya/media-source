package org.symera.mediasource.lib.vudeo

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.symera.source.model.SStream
import org.symera.source.online.GET
import org.symera.source.online.asJsoup

class VudeoExtractor(private val client: OkHttpClient) {
    fun streamsFromUrl(url: String, prefix: String = ""): List<SStream> {
        val doc = client.newCall(GET(url)).execute().asJsoup()
        val sources = doc.selectFirst("script:containsData(sources: [)")?.data() ?: return emptyList()
        val referer = "https://" + url.toHttpUrl().host + "/"
        val headers = Headers.headersOf("referer", referer)

        return sources.substringAfter("sources: [").substringBefore("]")
            .replace("\"", "")
            .split(',')
            .filter { it.startsWith("https") }
            .map { videoUrl -> SStream(url = videoUrl, title = "${prefix}Vudeo", headers = headers, initialized = true) }
    }

    fun videosFromUrl(url: String, prefix: String = ""): List<SStream> = streamsFromUrl(url, prefix)
}
