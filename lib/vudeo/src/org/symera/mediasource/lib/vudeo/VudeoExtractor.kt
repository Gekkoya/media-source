package org.symera.mediasource.lib.vudeo

import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.symera.source.model.HeaderScope
import org.symera.source.model.HttpHeader
import org.symera.source.model.MediaRequest
import org.symera.source.model.PlayableStream
import org.symera.source.model.SStream
import org.symera.source.network.awaitSuccess
import org.symera.source.online.GET
import java.net.URI

class VudeoExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {
    suspend fun streamsFromUrl(
        url: String,
        prefix: String = "",
    ): List<SStream> {
        val script = client.awaitSuccess(GET(url, headers)).use { Jsoup.parse(it.body.string()).selectFirst("script:containsData(sources: [)")?.data() }
            ?: return emptyList()
        return parseSources(script, url, prefix, headers)
    }

    internal companion object {
        fun parseSources(script: String, url: String, prefix: String = ""): List<PlayableStream> = parseSources(script, url, prefix, Headers.EMPTY)

        fun parseSources(script: String, url: String, prefix: String, headers: Headers): List<PlayableStream> {
            val sourceBody = Regex("""sources\s*:\s*\[([^]]*)]""").find(script)?.groupValues?.get(1) ?: return emptyList()
            val referer = runCatching { "https://${URI(url).host}/" }.getOrNull() ?: return emptyList()
            val title = "${prefix}Vudeo"
            return Regex("""https?://[^\"']+""").findAll(sourceBody).map { match ->
                val videoUrl = match.value
                PlayableStream(
                    id = videoUrl,
                    title = title,
                    request = MediaRequest(
                        uri = videoUrl,
                        headers = headers.toMultimap().flatMap { (name, values) -> values.map { HttpHeader(name, it) } } + HttpHeader("Referer", referer),
                        headerScope = HeaderScope.ALL_DERIVED_REQUESTS,
                    ),
                )
            }.toList()
        }
    }
}
