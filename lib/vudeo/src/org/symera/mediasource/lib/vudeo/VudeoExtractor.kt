package org.symera.mediasource.lib.vudeo

import okhttp3.Headers
import org.symera.mediasource.lib.webview.WebViewMediaResolver
import org.symera.source.model.PlayableStream
import org.symera.source.model.SStream
import org.symera.source.network.MediaBrowserFactory
import java.net.URI

/** Resolves Vudeo's JavaScript/fingerprint player and returns its media request. */
class VudeoExtractor(
    browserFactory: MediaBrowserFactory,
) {
    private val resolver = WebViewMediaResolver(browserFactory)

    suspend fun streamsFromUrl(
        url: String,
        headers: Headers = Headers.EMPTY,
        prefix: String = "",
    ): List<SStream> {
        val request = resolver.resolve(
            entryUrl = url,
            headers = headers,
            allowedTopLevelHosts = setOfNotNull(URI(url).host),
        )
        val title = listOf(prefix, "Vudeo").filter(String::isNotBlank).joinToString(" - ")
        return listOf(PlayableStream(id = request.uri, title = title, request = request))
    }
}
