package org.symera.mediasource.lib.rapidshare

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

class RapidShareExtractor(private val client: OkHttpClient, private val headers: Headers, private val context: Application? = null) {
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
            val iframeHeaders = headers.newBuilder().set("User-Agent", headers["User-Agent"] ?: DEFAULT_USER_AGENT).set("Referer", url).build()
            val html = client.newCall(GET(url, iframeHeaders)).awaitSuccess().bodyString()
            val realUrl = Regex("""<iframe[^>]+src=["']([^"']*(?:/e/|rapidshare)[^"']*)["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1)?.let { UrlUtils.fixUrl(it, "${parsedUrl.scheme}://${parsedUrl.host}") }
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
                                    if (iframe && (iframe.src.includes('/e/') || iframe.src.includes('rapidshare'))) return iframe.src;
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

    suspend fun streamsFromUrl(url: String, prefix: String, preferredLang: String): List<SStream> {
        val parsedUrl = url.toHttpUrlOrNull() ?: return emptyList()
        val userAgent = headers["User-Agent"] ?: DEFAULT_USER_AGENT
        if (parsedUrl.pathSegments.firstOrNull() == "iframe") return streamsFromUrl(unwrapIframeUrl(url), prefix, preferredLang)
        val rapidUrl = url.toHttpUrl()
        val token = rapidUrl.pathSegments.last()
        val subtitleUrl = rapidUrl.queryParameter("sub.list")
        val baseUrl = "${rapidUrl.scheme}://${rapidUrl.host}"
        val mediaHeaders = headers.newBuilder()
            .set("User-Agent", userAgent)
            .set("Accept", "application/json, text/plain, */*")
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Referer", url)
            .build()
        val encryptedResult = runCatching { client.newCall(GET("$baseUrl/media/$token", mediaHeaders)).awaitSuccess().parseAs<EncryptedRapidResponse>().result }.getOrNull()
            ?: return emptyList()
        val body = buildJsonObject {
            put("text", encryptedResult)
            put("agent", userAgent)
        }.toJsonRequestBody()
        val rapidResult = runCatching {
            client.newCall(POST("https://enc-dec.app/api/dec-rapid", body = body, headers = encDecHeaders(url))).awaitSuccess().parseAs<RapidDecryptResponse>().result
        }.getOrNull() ?: return emptyList()
        val subtitles = runCatching {
            if (subtitleUrl != null) {
                getSubtitles(subtitleUrl, baseUrl)
            } else {
                rapidResult.tracks
                    .filter { it.kind == "captions" && it.file.isNotBlank() && it.label != null }
                    .map { SubtitleTrack(it.file, it.label!!) }
            }
        }.getOrDefault(emptyList())

        return rapidResult.sources.flatMap { source ->
            val videoUrl = source.file
            if (videoUrl.contains(".m3u8")) {
                playlistUtils.extractFromHls(videoUrl, referer = "$baseUrl/", videoNameGen = { quality -> "$prefix - $quality" }, subtitleList = subLangSelect(subtitles, preferredLang))
            } else {
                emptyList()
            }
        }
    }

    suspend fun videosFromUrl(url: String, prefix: String, preferredLang: String): List<SStream> = streamsFromUrl(url, prefix, preferredLang)

    private suspend fun getSubtitles(url: String, baseUrl: String): List<SubtitleTrack> {
        val subHeaders = headers.newBuilder().set("Accept", "*/*").set("Origin", baseUrl).set("Referer", "$baseUrl/").build()
        return client.newCall(GET(url, subHeaders)).awaitSuccess().parseAs<List<RapidShareTrack>>()
            .filter { it.kind == "captions" && it.file.isNotBlank() && it.label != null }
            .map { SubtitleTrack(it.file, it.label!!) }
    }

    private fun subLangSelect(tracks: List<SubtitleTrack>, language: String): List<SubtitleTrack> = tracks.sortedByDescending { it.lang.contains(language, true) }

    companion object {
        private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"
    }
}

@Serializable
data class EncryptedRapidResponse(val result: String)

@Serializable
data class RapidDecryptResponse(val status: Int, val result: RapidShareResult)

@Serializable
data class RapidShareResult(val sources: List<RapidShareSource> = emptyList(), val tracks: List<RapidShareTrack> = emptyList())

@Serializable
data class RapidShareSource(val file: String)

@Serializable
data class RapidShareTrack(val file: String, val label: String? = null, val kind: String)
