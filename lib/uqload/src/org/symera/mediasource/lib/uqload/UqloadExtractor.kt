package org.symera.mediasource.lib.uqload

import okhttp3.OkHttpClient
import org.symera.mediasource.core.useAsJsoup
import org.symera.mediasource.lib.unpacker.autoUnpacker
import org.symera.source.model.HeaderScope
import org.symera.source.model.HttpHeader
import org.symera.source.model.MediaRequest
import org.symera.source.model.PlayableStream
import org.symera.source.model.SStream
import org.symera.source.network.awaitSuccess
import org.symera.source.online.GET

class UqloadExtractor(private val client: OkHttpClient) {
    suspend fun streamsFromUrl(url: String, prefix: String = ""): List<SStream> {
        val fixedUrl = if (url.startsWith(BASE_URL, true)) url else url.replace(hostRegex, BASE_URL)
        val doc = client.awaitSuccess(GET(fixedUrl)).useAsJsoup()
        val script = doc.selectFirst("script:containsData(sources:)")?.data()
            ?: doc.selectFirst("script:containsData(eval)")?.data()
            ?: return emptyList()
        return parseSource(script, prefix)?.let(::listOf).orEmpty()
    }

    suspend fun videosFromUrl(url: String, prefix: String = ""): List<SStream> = streamsFromUrl(url, prefix)

    companion object {
        const val BASE_URL = "https://uqload.is/"
        private val hostRegex by lazy { Regex("""https?://(?:www\.)?[^/]+/""") }
        private val VIDEO_URL = Regex("""(?:file|sources)\s*:\s*(?:\[\s*)?(?:\{\s*)?[\"'](https?://[^\"']+)[\"']""")

        fun parseSource(script: String, prefix: String = ""): PlayableStream? {
            val unpacked = if (script.contains("eval(function(p,a,c")) autoUnpacker(script) ?: return null else script
            val videoUrl = VIDEO_URL.find(unpacked)?.groupValues?.get(1)?.replace("\\/", "/") ?: return null
            val quality = if (prefix.isNotBlank()) "${prefix.trim()} Uqload" else "Uqload"
            return PlayableStream(
                id = videoUrl,
                title = quality,
                request = MediaRequest(
                    uri = videoUrl,
                    headers = listOf(HttpHeader("Referer", BASE_URL)),
                    headerScope = HeaderScope.ALL_DERIVED_REQUESTS,
                ),
            )
        }
    }
}
