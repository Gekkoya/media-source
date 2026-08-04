package org.symera.mediasource.lib.okru

import okhttp3.Headers
import okhttp3.OkHttpClient
import org.symera.mediasource.core.commonEmptyHeaders
import org.symera.mediasource.core.useAsJsoup
import org.symera.mediasource.lib.playlistutils.PlaylistUtils
import org.symera.source.model.MediaRequest
import org.symera.source.model.HeaderScope
import org.symera.source.model.HttpHeader
import org.symera.source.model.PlayableStream
import org.symera.source.model.SStream
import org.symera.source.online.GET

class OkruExtractor(private val client: OkHttpClient, private val headers: Headers = commonEmptyHeaders) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun streamsFromUrl(url: String, prefix: String = "", fixQualities: Boolean = true): List<SStream> {
        val sourceUrl = normalizeHttpsUrl(url)
        val document = client.newCall(GET(sourceUrl, headers)).execute().useAsJsoup()
        val videoString = document.selectFirst("div[data-options]")?.attr("data-options") ?: return emptyList()

        val options = parseOptions(videoString)
        return when {
            options.hlsUrl != null -> {
                val playlistUrl = normalizeHttpsUrl(options.hlsUrl)
                playlistUtils.withDirectHlsFallback(
                    playlistUrl = playlistUrl,
                    title = "Okru:HLS".addPrefix(prefix),
                    headers = headers.withReferer(sourceUrl),
                ) {
                    playlistUtils.extractFromHls(
                        playlistUrl,
                        referer = sourceUrl,
                        masterHeaders = headers.withReferer(sourceUrl),
                        videoHeaders = headers.withReferer(sourceUrl),
                        videoNameGen = { "Okru:$it".addPrefix(prefix) },
                    )
                }
            }
            options.dashUrl != null -> {
                val playlistUrl = normalizeHttpsUrl(options.dashUrl)
                val dashHeaders = headers.withReferer(sourceUrl)
                playlistUtils.withDirectHlsFallback(
                    playlistUrl = playlistUrl,
                    title = "Okru:DASH".addPrefix(prefix),
                    headers = dashHeaders,
                ) {
                    playlistUtils.extractFromDash(
                        playlistUrl,
                        videoNameGen = { "Okru:$it".addPrefix(prefix) },
                        mpdHeaders = dashHeaders,
                        videoHeaders = dashHeaders,
                        referer = sourceUrl,
                    )
                }
            }
            else -> parseVideos(videoString, prefix, fixQualities, headers.withReferer(sourceUrl))
        }
    }

    private fun String.addPrefix(prefix: String) = prefix.takeIf(String::isNotBlank)?.let { "$prefix $this" } ?: this

    private fun Headers.withReferer(url: String): Headers = newBuilder()
        .set("Referer", url)
        .set("User-Agent", USER_AGENT)
        .build()

    internal data class ParsedOptions(val hlsUrl: String?, val dashUrl: String?)

    internal companion object {
        fun parseOptions(videoString: String): ParsedOptions {
            val normalized = videoString.replace("\\\"", "\"").replace("\\\\u0026", "&").replace("\\u0026", "&")
            fun link(name: String) = Regex("""[\"']$name[\"']\s*:\s*[\"']([^\"']+)[\"']""").find(normalized)?.groupValues?.get(1)
            return ParsedOptions(link("ondemandHls"), link("ondemandDash"))
        }

        fun parseVideos(videoString: String, prefix: String = "", fixQualities: Boolean = true, referer: String? = null): List<PlayableStream> =
            parseVideos(videoString, prefix, fixQualities, referer?.let { Headers.headersOf("Referer", it) } ?: Headers.EMPTY)
    }

}

private fun normalizeHttpsUrl(url: String): String = url.replaceFirst("^http://".toRegex(), "https://")

private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"

private fun parseVideos(videoString: String, prefix: String, fixQualities: Boolean, requestHeaders: Headers): List<PlayableStream> {
    val normalized = videoString.replace("\\\"", "\"").replace("\\u0026", "&")
    val videoRegex = Regex("""[\"']name[\"']\s*:\s*[\"']([^\"']+)[\"']\s*,\s*[\"']url[\"']\s*:\s*[\"']([^\"']+)[\"']""")
    val qualities = mapOf("ultra" to "2160p", "quad" to "1440p", "full" to "1080p", "hd" to "720p", "sd" to "480p", "low" to "360p", "lowest" to "240p", "mobile" to "144p")
    return videoRegex.findAll(normalized).mapNotNull { match ->
        val quality = match.groupValues[1].let { if (fixQualities) qualities[it] ?: it else it }
        val videoUrl = match.groupValues[2]
        if (!videoUrl.startsWith("https://")) return@mapNotNull null
        PlayableStream(
            id = videoUrl,
            title = listOf(prefix.takeIf(String::isNotBlank), "Okru:$quality").filterNotNull().joinToString(" "),
            request = MediaRequest(
                uri = videoUrl,
                headers = requestHeaders.toMultimap().flatMap { (name, values) -> values.map { HttpHeader(name, it) } },
                headerScope = HeaderScope.ALL_DERIVED_REQUESTS,
            ),
        )
    }.toList()
}
