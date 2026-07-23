package org.symera.mediasource.lib.universal

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.symera.mediasource.lib.playlistutils.PlaylistUtils
import org.symera.mediasource.lib.webview.WebViewMediaResolver
import org.symera.source.model.HeaderScope
import org.symera.source.model.HttpHeader
import org.symera.source.model.PlayableStream
import org.symera.source.model.SStream
import org.symera.source.network.MediaBrowserFactory

/** Generic browser-backed extractor. Host-specific rules stay in media-source modules. */
class UniversalExtractor(
    private val client: okhttp3.OkHttpClient,
    browserFactory: MediaBrowserFactory?,
) {
    private val resolver = browserFactory?.let(::WebViewMediaResolver)

    suspend fun streamsFromUrl(
        origRequestUrl: String,
        origRequestHeader: Headers,
        customQuality: String? = null,
        prefix: String = "",
    ): List<SStream> {
        val mediaResolver = resolver ?: return emptyList()
        val request = runCatching {
            mediaResolver.resolve(
                entryUrl = origRequestUrl,
                headers = origRequestHeader,
                mediaUrlPattern = MEDIA_URL_PATTERN,
            )
        }.getOrNull() ?: return emptyList()
        val mediaHeaders = request.headers.toHeaders()
        val host = origRequestUrl.toHttpUrl().host.substringBefore('.')
        val titlePrefix = listOf(prefix, host).filter(String::isNotBlank).joinToString(" - ")
        val playlistUtils = PlaylistUtils(client, mediaHeaders)
        val playbackRequest = request.copy(headerScope = HeaderScope.ALL_DERIVED_REQUESTS)

        return when {
            request.uri.contains("m3u8", ignoreCase = true) -> {
                runCatching {
                    playlistUtils.extractFromHls(
                        playlistUrl = request.uri,
                        referer = origRequestUrl,
                        masterHeaders = mediaHeaders,
                        videoHeaders = mediaHeaders,
                        videoNameGen = { "$titlePrefix - $it" },
                    ).map { stream ->
                        if (stream is PlayableStream) stream.copy(request = stream.request.copy(headerScope = HeaderScope.ALL_DERIVED_REQUESTS)) else stream
                    }
                }.getOrElse {
                    listOf(PlayableStream(request.uri, "$titlePrefix - HLS", playbackRequest))
                }
            }
            request.uri.contains("mpd", ignoreCase = true) -> {
                playlistUtils.extractFromDash(
                    request.uri,
                    { quality -> "$titlePrefix - $quality" },
                    mpdHeaders = mediaHeaders,
                    videoHeaders = mediaHeaders,
                    referer = origRequestUrl,
                )
            }
            request.uri.contains("mp4", ignoreCase = true) -> {
                listOf(PlayableStream(request.uri, "$titlePrefix - ${customQuality ?: "Mirror"}", playbackRequest))
            }
            else -> emptyList()
        }
    }

    companion object {
        private val MEDIA_URL_PATTERN = Regex("(?i).*\\.(m3u8|mp4|mpd)(\\?.*)?$").pattern
    }
}

private fun List<HttpHeader>.toHeaders(): Headers = Headers.Builder().apply {
    forEach { header -> set(header.name, header.value) }
}.build()
