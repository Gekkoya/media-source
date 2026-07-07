package org.symera.mediasource.lib.uqload

import okhttp3.Headers
import okhttp3.OkHttpClient
import org.symera.mediasource.core.useAsJsoup
import org.symera.source.model.SStream
import org.symera.source.online.GET
import org.symera.source.online.awaitSuccess

class UqloadExtractor(private val client: OkHttpClient) {
    suspend fun streamsFromUrl(url: String, prefix: String = ""): List<SStream> {
        val fixedUrl = if (url.startsWith(BASE_URL, true)) url else url.replace(hostRegex, BASE_URL)
        val doc = client.newCall(GET(fixedUrl)).awaitSuccess().useAsJsoup()
        val script = doc.selectFirst("script:containsData(sources:)")?.data() ?: return emptyList()
        val videoUrl = script.substringAfter("sources: [\"").substringBefore('"')
            .takeIf(String::isNotBlank)
            ?.takeIf { it.startsWith("http") }
            ?: return emptyList()
        val quality = if (prefix.isNotBlank()) "${prefix.trim()} Uqload" else "Uqload"
        return listOf(SStream(url = videoUrl, title = quality, headers = Headers.headersOf("Referer", BASE_URL), initialized = true))
    }

    suspend fun videosFromUrl(url: String, prefix: String = ""): List<SStream> = streamsFromUrl(url, prefix)

    companion object {
        const val BASE_URL = "https://uqload.is/"
        private val hostRegex by lazy { Regex("""https?://(?:www\.)?[^/]+/""") }
    }
}
