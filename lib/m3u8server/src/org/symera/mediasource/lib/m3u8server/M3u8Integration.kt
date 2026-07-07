package org.symera.mediasource.lib.m3u8server

import android.util.Log
import okhttp3.OkHttpClient
import org.symera.source.model.SStream

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

    private fun processM3u8Stream(original: SStream): SStream = original.copy(url = serverManager.processM3u8Url(original.url) ?: original.url)

    fun processStreamList(streams: List<SStream>): List<SStream> {
        initializeServer()
        return streams.map { if (isM3u8Url(it.url)) processM3u8Stream(it) else it }
    }

    fun processVideoList(streams: List<SStream>): List<SStream> = processStreamList(streams)

    private fun isM3u8Url(url: String): Boolean = Regex("""\.m3u8($|\?|#)""", RegexOption.IGNORE_CASE).containsMatchIn(url) ||
        url.contains("application/vnd.apple.mpegurl", ignoreCase = true)

    fun getServerInfo(): String = serverManager.getServerInfo()
    fun stopServer() = serverManager.stopServer()
    fun isServerRunning(): Boolean = serverManager.isRunning()
}
