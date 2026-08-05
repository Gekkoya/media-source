package org.symera.mediasource.lib.webview

import okhttp3.Headers
import org.symera.source.model.HttpHeader
import org.symera.source.model.MediaRequest
import org.symera.source.network.MediaBrowserFactory
import org.symera.source.network.MediaBrowserRequest

/** Resolves browser-only hosters without exposing their HTML UI to the player. */
class WebViewMediaResolver(
    private val browserFactory: MediaBrowserFactory,
) {
    suspend fun resolve(
        entryUrl: String,
        headers: Headers = Headers.EMPTY,
        mediaUrlPattern: String = DEFAULT_MEDIA_URL_PATTERN,
        allowedTopLevelHosts: Set<String> = emptySet(),
    ): MediaRequest {
        val requestHeaders = headers.toMultimap().mapValues { it.value.lastOrNull().orEmpty() }
        val result = browserFactory.create().use { browser ->
            browser.resolve(
                MediaBrowserRequest(
                    entryUrl = entryUrl,
                    headers = requestHeaders,
                    mediaUrlPattern = mediaUrlPattern,
                    allowedTopLevelHosts = allowedTopLevelHosts,
                ),
            )
        }
        return MediaRequest(
            uri = result.mediaUrl,
            headers = result.headers.map { (name, value) -> HttpHeader(name, value) },
        )
    }

    companion object {
        const val DEFAULT_MEDIA_URL_PATTERN = "(?i).*\\.(m3u8|mp4|mpd)(\\?.*)?$"

        fun isAllowedMediaHost(host: String?, allowedHosts: Set<String>): Boolean = host?.lowercase()?.let(allowedHosts::contains) == true
    }
}
