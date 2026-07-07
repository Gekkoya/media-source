package org.symera.mediasource.lib.megaup

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.symera.mediasource.core.UrlUtils
import org.symera.mediasource.core.bodyString
import org.symera.mediasource.core.parseAs
import org.symera.mediasource.core.toJsonRequestBody
import org.symera.mediasource.lib.playlistutils.PlaylistUtils
import org.symera.source.model.SStream
import org.symera.source.model.SubtitleTrack
import org.symera.source.online.GET
import org.symera.source.online.POST
import org.symera.source.online.awaitSuccess
import kotlin.coroutines.resume

class MegaUpExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val context: Application? = null,
) {
    private val tag by lazy { javaClass.simpleName }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private fun encDecHeaders(url: String): Headers {
        val referer = headers["Referer"] ?: url
        val origin = referer.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" }
        return headers.newBuilder().apply {
            set("User-Agent", headers["User-Agent"] ?: DEFAULT_USER_AGENT)
            set("Accept", "application/json, text/plain, */*")
            origin?.let { set("Origin", it) }
            set("Referer", referer)
            set("Sec-Fetch-Dest", "empty")
            set("Sec-Fetch-Mode", "cors")
            set("Sec-Fetch-Site", "cross-site")
        }.build()
    }

    private suspend fun unwrapIframeUrl(url: String): String {
        try {
            val parsedUrl = url.toHttpUrl()
            val iframeHeaders = headers.newBuilder().apply {
                set("User-Agent", headers["User-Agent"] ?: DEFAULT_USER_AGENT)
                set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                set("Referer", url)
            }.build()
            val html = client.newCall(GET(url, iframeHeaders)).awaitSuccess().bodyString()
            val iframeRegex = Regex("""<iframe[^>]+src=["']([^"']*(?:/e/|megaup)[^"']*)["']""", RegexOption.IGNORE_CASE)
            val realUrl = iframeRegex.find(html)?.groupValues?.getOrNull(1)?.let { UrlUtils.fixUrl(it, "${parsedUrl.scheme}://${parsedUrl.host}") }
            if (!realUrl.isNullOrBlank()) return realUrl
        } catch (_: Exception) {
            Log.d(tag, "OkHttp unwrap failed, falling back to WebView")
        }
        if (context != null) return withTimeout(15_000) { unwrapWithWebView(url) }
        error("Server is protected by Cloudflare Turnstile. Cannot extract video.")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun unwrapWithWebView(url: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val webView = WebView(context!!).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = headers["User-Agent"] ?: DEFAULT_USER_AGENT
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, loadedUrl: String) {
                        if (loadedUrl.contains("/cdn-cgi/")) return
                        view.evaluateJavascript(
                            """
                            (function() {
                                try {
                                    var iframe = document.querySelector('iframe[src]');
                                    if (iframe && (iframe.src.includes('/e/') || iframe.src.includes('megaup'))) return iframe.src;
                                } catch(e) {}
                                return '';
                            })();
                            """.trimIndent(),
                        ) { result ->
                            val extractedUrl = result?.replace("\\\"", "\"")?.trim('"')?.takeIf { it.isNotEmpty() && it != "null" }
                            if (extractedUrl != null) {
                                view.destroy()
                                if (continuation.isActive) continuation.resume(extractedUrl)
                            }
                        }
                    }
                }
                loadUrl(url)
            }
            continuation.invokeOnCancellation { Handler(Looper.getMainLooper()).post { webView.destroy() } }
        }
    }

    suspend fun streamsFromUrl(url: String, serverName: String? = null): List<SStream> {
        val parsedUrl = url.toHttpUrlOrNull() ?: return emptyList()
        val userAgent = headers["User-Agent"] ?: DEFAULT_USER_AGENT
        if (parsedUrl.pathSegments.firstOrNull() == "iframe") return streamsFromUrl(unwrapIframeUrl(url), serverName)
        val megaHost = "${parsedUrl.scheme}://${parsedUrl.host}"
        val prefix = serverName ?: extractHoster(parsedUrl.host).proper()
        val token = parsedUrl.pathSegments.lastOrNull(String::isNotBlank) ?: error("No token found in URL: $url")
        val mediaHeaders = headers.newBuilder().apply {
            set("User-Agent", userAgent)
            set("Accept", "application/json, text/plain, */*")
            set("X-Requested-With", "XMLHttpRequest")
            set("Referer", url)
        }.build()
        val megaToken = client.newCall(GET("$megaHost/media/$token", mediaHeaders)).awaitSuccess().parseAs<InternalEncryptedResponse>().result
        val tokenBody = buildJsonObject {
            put("text", megaToken)
            put("agent", userAgent)
        }.toJsonRequestBody()
        val megaUpResult = client.newCall(POST("https://enc-dec.app/api/dec-mega", body = tokenBody, headers = encDecHeaders(url)))
            .awaitSuccess().parseAs<InternalTokenResponse>().result
        val subtitleTracks = megaUpResult.subtitleTracks()
        val videoHeaders = headers.newBuilder().set("User-Agent", userAgent).set("Origin", megaHost).set("Referer", "$megaHost/").build()

        return megaUpResult.sources.flatMap {
            val videoUrl = it.file
            when {
                m3u8Regex.containsMatchIn(videoUrl) -> playlistUtils.extractFromHls(videoUrl, referer = "$megaHost/", subtitleList = subtitleTracks, videoNameGen = { q -> "$prefix: $q" })
                mpdRegex.containsMatchIn(videoUrl) -> playlistUtils.extractFromDash(videoUrl, videoNameGen = { q -> "$prefix: $q" }, subtitleList = subtitleTracks, referer = "$megaHost/")
                mp4Regex.containsMatchIn(videoUrl) -> listOf(SStream(url = videoUrl, title = "$prefix: MP4", headers = videoHeaders, subtitleTracks = subtitleTracks, initialized = true))
                else -> emptyList()
            }
        }
    }

    suspend fun videosFromUrl(url: String, serverName: String? = null): List<SStream> = streamsFromUrl(url, serverName)

    private val m3u8Regex by lazy { Regex(""".+\.m3u8(?:\?.*)?$""", RegexOption.IGNORE_CASE) }
    private val mpdRegex by lazy { Regex(""".+\.mpd(?:\?.*)?$""", RegexOption.IGNORE_CASE) }
    private val mp4Regex by lazy { Regex(""".+\.mp4(?:\?.*)?$""", RegexOption.IGNORE_CASE) }
    private val hosterRegex by lazy { Regex("""([^.]+)\.[^.]+$""") }
    private fun extractHoster(host: String): String = hosterRegex.find(host)?.groupValues?.getOrNull(1) ?: host
    private fun String.proper(): String = replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    @Serializable
    private data class InternalEncryptedResponse(val result: String)

    @Serializable
    private data class InternalTokenResponse(val result: InternalMegaUpResult)

    @Serializable
    private data class InternalMegaUpResult(val sources: List<InternalMegaUpSource>, val tracks: List<InternalMegaUpTrack> = emptyList()) {
        fun subtitleTracks(): List<SubtitleTrack> = tracks
            .filter { it.kind == "captions" && it.file.endsWith(".vtt", ignoreCase = true) }
            .sortedByDescending { it.default }
            .map { SubtitleTrack(it.file, it.label ?: "Unknown") }
    }

    @Serializable
    private data class InternalMegaUpSource(val file: String)

    @Serializable
    private data class InternalMegaUpTrack(val file: String, val label: String? = null, val kind: String, val default: Boolean = false)

    companion object {
        private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"
    }
}
