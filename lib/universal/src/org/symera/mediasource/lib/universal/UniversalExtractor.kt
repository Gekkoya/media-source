package org.symera.mediasource.lib.universal

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.symera.mediasource.lib.playlistutils.PlaylistUtils
import org.symera.source.model.HttpHeader
import org.symera.source.model.MediaRequest
import org.symera.source.model.PlayableStream
import org.symera.source.model.SStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UniversalExtractor(private val client: OkHttpClient) {
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    @SuppressLint("SetJavaScriptEnabled")
    fun streamsFromUrl(origRequestUrl: String, origRequestHeader: Headers, customQuality: String? = null, prefix: String = ""): List<SStream> {
        val context = defaultApplicationProvider() ?: currentApplication() ?: return emptyList()
        val host = origRequestUrl.toHttpUrl().host.substringBefore(".").proper()
        val latch = CountDownLatch(1)
        var webView: WebView? = null
        var resultUrl = ""
        val playlistUtils by lazy { PlaylistUtils(client, origRequestHeader) }
        val headers = origRequestHeader.toMultimap().mapValues { it.value.getOrNull(0) ?: "" }.toMutableMap()

        handler.post {
            val newView = WebView(context)
            webView = newView
            with(newView.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = false
                loadWithOverviewMode = false
                userAgentString = origRequestHeader["User-Agent"]
            }
            newView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url.toString()
                    if (VIDEO_REGEX.containsMatchIn(url)) {
                        resultUrl = url
                        latch.countDown()
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }
            newView.loadUrl(origRequestUrl, headers)
        }

        latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }

        if ("M3U8_AUTO_360" in resultUrl) {
            val qualities = listOf("1080", "720", "480", "360")
            val allStreams = mutableListOf<SStream>()
            for (quality in qualities) {
                val modifiedUrl = resultUrl.replace("M3U8_AUTO_360", "M3U8_AUTO_$quality")
                val streams = playlistUtils.extractFromHls(modifiedUrl, origRequestUrl, videoNameGen = { "$prefix - $host: $it ${quality}p" })
                if (streams.isNotEmpty()) allStreams.addAll(streams)
            }
            if (allStreams.isNotEmpty()) return allStreams
        }

        return when {
            "m3u8" in resultUrl -> {
                Log.d("UniversalExtractor", "m3u8 URL: $resultUrl")
                playlistUtils.extractFromHls(resultUrl, origRequestUrl, videoNameGen = { "$prefix - $host: $it" })
            }
            "mpd" in resultUrl -> {
                Log.d("UniversalExtractor", "mpd URL: $resultUrl")
                playlistUtils.extractFromDash(resultUrl, { quality -> "$prefix - $host: $quality" }, referer = origRequestUrl)
            }
            "mp4" in resultUrl -> {
                Log.d("UniversalExtractor", "mp4 URL: $resultUrl")
                listOf(
                    PlayableStream(
                        id = resultUrl,
                        title = "$prefix - $host: ${customQuality ?: "Mirror"}",
                        request = MediaRequest(
                            uri = resultUrl,
                            headers = origRequestHeader.newBuilder().add("Referer", origRequestUrl).build().toMultimap().flatMap { (name, values) -> values.map { HttpHeader(name, it) } },
                        ),
                    ),
                )
            }
            else -> emptyList()
        }
    }

    private fun String.proper(): String = replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
    }

    companion object {
        const val TIMEOUT_SEC: Long = 10
        private val VIDEO_REGEX by lazy { Regex(".*\\.(mp4|m3u8|mpd)(\\?.*)?$") }

        @Volatile
        var defaultApplicationProvider: () -> Application? = { null }

        @Suppress("PrivateApi")
        private fun currentApplication(): Application? = runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            activityThread.getMethod("currentApplication").invoke(null) as? Application
        }.getOrNull()
    }
}
