package org.symera.mediasource.lib.dood

import okhttp3.Headers
import okhttp3.OkHttpClient
import org.symera.source.model.HttpHeader
import org.symera.source.model.MediaRequest
import org.symera.source.model.PlayableStream
import org.symera.source.model.SStream
import org.symera.source.model.SubtitleTrack
import org.symera.source.online.GET
import java.net.URI

class DoodExtractor(private val client: OkHttpClient) {
    fun streamFromUrl(
        url: String,
        prefix: String? = null,
        redirect: Boolean = true,
        externalSubs: List<SubtitleTrack> = emptyList(),
    ): SStream? {
        return runCatching {
            val response = client.newCall(GET(url)).execute()
            val newUrl = if (redirect) response.request.url.toString() else url
            val doodBaseUrl = getBaseUrl(newUrl)
            val doodHost = URI(newUrl).host
            val content = response.body.string()
            if (!content.contains("'/pass_md5/")) return null

            val extractedQuality = Regex("\\d{3,4}p")
                .find(content.substringAfter("<title>").substringBefore("</title>"))
                ?.groupValues
                ?.getOrNull(0)

            val streamTitle = listOfNotNull(
                prefix,
                "Doodstream " + (extractedQuality ?: if (redirect) "mirror" else ""),
            ).joinToString(" - ")

            val md5 = doodBaseUrl + (Regex("/pass_md5/[^']*").find(content)?.value ?: return null)
            val token = md5.substringAfterLast("/")
            val randomString = createHashTable()
            val expiry = System.currentTimeMillis()

            val videoUrlStart = client.newCall(
                GET(
                    md5,
                    Headers.headersOf("Referer", newUrl),
                ),
            ).execute().body.string()
            val videoUrl = "$videoUrlStart$randomString?token=$token&expiry=$expiry"
            PlayableStream(
                id = videoUrl,
                title = streamTitle,
                request = MediaRequest(uri = videoUrl, headers = doodHeaders(doodHost).toMultimap().flatMap { (name, values) -> values.map { HttpHeader(name, it) } }),
                subtitleTracks = externalSubs,
            )
        }.getOrNull()
    }

    fun streamsFromUrl(url: String, quality: String? = null, redirect: Boolean = true): List<SStream> = streamFromUrl(url, quality, redirect)?.let(::listOf) ?: emptyList()

    private fun createHashTable(length: Int = 10): String {
        val alphabet = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        return buildString { repeat(length) { append(alphabet.random()) } }
    }

    private fun getBaseUrl(url: String): String = URI(url).let { "${it.scheme}://${it.host}" }

    private fun doodHeaders(host: String) = Headers.Builder().apply {
        add("User-Agent", "Symera")
        add("Referer", "https://$host/")
    }.build()
}
