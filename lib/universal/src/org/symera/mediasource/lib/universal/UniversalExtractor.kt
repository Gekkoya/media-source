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
import kotlin.coroutines.cancellation.CancellationException

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
        allowedTopLevelHosts: Set<String> = emptySet(),
    ): List<SStream> {
        val mediaResolver = resolver ?: return emptyList()
        val request = try {
            mediaResolver.resolve(
                entryUrl = origRequestUrl,
                headers = origRequestHeader,
                mediaUrlPattern = MEDIA_URL_PATTERN,
                allowedTopLevelHosts = allowedTopLevelHosts,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return emptyList()
        }
        val mediaHeaders = request.headers.toHeaders()
        val host = origRequestUrl.toHttpUrl().host.substringBefore('.')
        val titlePrefix = listOf(prefix, host).filter(String::isNotBlank).joinToString(" - ")
        val playlistUtils = PlaylistUtils(client, mediaHeaders)
        // Keep browser-captured referer from nested embeds; hoster entry URL is only fallback.
        val mediaReferer = mediaHeaders["Referer"]?.takeIf(String::isNotBlank) ?: origRequestUrl
        val mediaHeadersWithReferer = playlistUtils.generateMasterHeaders(mediaHeaders, mediaReferer)
        val playbackRequest = request.copy(headerScope = HeaderScope.ALL_DERIVED_REQUESTS)

        return when {
            request.uri.contains("m3u8", ignoreCase = true) -> {
                val extracted = playlistUtils.withDirectHlsFallback(
                    playlistUrl = request.uri,
                    title = "$titlePrefix - HLS",
                    headers = mediaHeadersWithReferer,
                ) {
                    playlistUtils.extractFromHls(
                        playlistUrl = request.uri,
                        referer = mediaReferer,
                        masterHeaders = mediaHeadersWithReferer,
                        videoHeaders = mediaHeadersWithReferer,
                        videoNameGen = { "$titlePrefix - $it" },
                    ).map { stream ->
                        if (stream is PlayableStream) stream.copy(request = stream.request.copy(headerScope = HeaderScope.ALL_DERIVED_REQUESTS)) else stream
                    }
                }
                if (extracted.isEmpty()) listOf(playlistUtils.directHlsFallback(request.uri, "$titlePrefix - HLS", mediaHeadersWithReferer)) else extracted
            }
            request.uri.contains("mpd", ignoreCase = true) -> {
                val dashHeaders = mediaHeadersWithReferer
                val dashPlaybackRequest = playbackRequest.copy(headers = dashHeaders.toHttpHeaders())
                try {
                    playlistUtils.extractFromDash(
                        request.uri,
                        { quality -> "$titlePrefix - $quality" },
                        mpdHeaders = dashHeaders,
                        videoHeaders = dashHeaders,
                        referer = mediaReferer,
                    ).ifEmpty {
                        listOf(PlayableStream(request.uri, "$titlePrefix - DASH", dashPlaybackRequest))
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    listOf(PlayableStream(request.uri, "$titlePrefix - DASH", dashPlaybackRequest))
                }
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

private fun Headers.toHttpHeaders(): List<HttpHeader> = toMultimap().flatMap { (name, values) ->
    values.map { value -> HttpHeader(name, value) }
}
