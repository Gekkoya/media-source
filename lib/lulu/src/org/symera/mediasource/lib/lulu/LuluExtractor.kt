package org.symera.mediasource.lib.lulu

import android.util.Log
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.symera.mediasource.lib.playlistutils.PlaylistUtils
import org.symera.mediasource.lib.unpacker.autoUnpacker
import org.symera.source.model.SStream
import org.symera.source.network.awaitSuccess
import org.symera.source.online.GET
import kotlin.coroutines.cancellation.CancellationException

class LuluExtractor(
    private val client: OkHttpClient,
    private val headers: Headers = Headers.headersOf(),
) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val requestHeaders = headers.newBuilder()
        .add("Referer", "https://luluvdo.com/")
        .add("Origin", "https://luluvdo.com")
        .build()

    suspend fun streamsFromUrl(url: String, prefix: String = ""): List<SStream> = try {
        val html = client.awaitSuccess(GET(url, requestHeaders)).use { it.body.string() }
        val hlsUrl = extractSource(html) ?: return emptyList()
        playlistUtils.extractFromHls(
            hlsUrl,
            referer = "https://luluvdo.com/",
            masterHeaders = requestHeaders,
            videoHeaders = requestHeaders,
        ) { quality -> "$prefix Lulu: $quality" }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        Log.w("LuluExtractor", "failure host=${url.toHttpUrlOrNull()?.host ?: "unknown"} category=${error.javaClass.simpleName}")
        emptyList()
    }

    internal companion object {
        val SOURCE_REGEX = Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*[\"']([^\"']+)[\"']""")

        fun extractSource(html: String, unpacker: (String) -> String? = ::autoUnpacker): String? {
            val page = if (html.contains("eval(function(p,a,c,k,e")) unpacker(html) ?: return null else html
            val source = SOURCE_REGEX.find(page)?.groupValues?.get(1)?.replace("\\/", "/") ?: return null
            val parsed = source.toHttpUrlOrNull() ?: return null
            if (!parsed.toString().contains(".m3u8", ignoreCase = true)) return null

            val query = source.substringAfter('?', "")
            val positional = mutableMapOf<String, String>()
            val named = linkedMapOf<String, String>()
            var position = 0
            query.split('&').filter(String::isNotEmpty).forEach { part ->
                val pieces = part.split('=', limit = 2)
                val key = pieces.first()
                val value = pieces.getOrElse(1) { "" }
                if (key.isBlank()) {
                    POSITIONAL_KEYS.getOrNull(position++)?.let { positional[it] = value }
                } else {
                    named[key] = value
                }
            }
            val builder = source.substringBefore('?').toHttpUrlOrNull()?.newBuilder() ?: return null
            builder.apply {
                POSITIONAL_KEYS.forEach { key -> positional[key]?.let { addQueryParameter(key, it) } }
                named.filterKeys { it != "i" && it != "sp" }.forEach { (key, value) -> addQueryParameter(key, value) }
                addQueryParameter("i", "0.3")
                addQueryParameter("sp", "0")
            }
            return builder.build().toString()
        }

        private val POSITIONAL_KEYS = listOf("t", "s", "e", "f")
    }
}
