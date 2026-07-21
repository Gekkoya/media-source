package org.symera.mediasource.lib.m3u8server

import android.util.Log
import okhttp3.OkHttpClient
import org.symera.source.model.PlayableStream

class M3u8Integration(
    client: OkHttpClient,
    private val serverManager: M3u8ServerManager = M3u8ServerManager(client),
) {
    private val tag by lazy { javaClass.simpleName }

    private fun initializeServer() {
        if (!serverManager.isRunning()) {
            runCatching { serverManager.startServer() }
                .onFailure { Log.e(tag, "Failed to start M3U8 server: ${it.message}", it) }
        }
    }

    private fun processM3u8Stream(original: PlayableStream): PlayableStream = original.copy(
        request = original.request.copy(uri = serverManager.processM3u8Url(original.request.uri) ?: original.request.uri),
    )

    fun processStreamList(streams: List<PlayableStream>): List<PlayableStream> {
        initializeServer()
        return streams.map { if (isM3u8Url(it.request.uri)) processM3u8Stream(it) else it }
    }

    fun processVideoList(streams: List<PlayableStream>): List<PlayableStream> = processStreamList(streams)

    private fun isM3u8Url(url: String): Boolean = Regex("""\.m3u8($|\?|#)""", RegexOption.IGNORE_CASE).containsMatchIn(url) ||
        url.contains("application/vnd.apple.mpegurl", ignoreCase = true)

    fun getServerInfo(): String = serverManager.getServerInfo()
    fun stopServer() = serverManager.stopServer()
    fun isServerRunning(): Boolean = serverManager.isRunning()
}
