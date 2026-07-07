package org.symera.mediasource.lib.m3u8server

import android.util.Log
import okhttp3.OkHttpClient

class M3u8ServerManager(private val client: OkHttpClient) {
    private val tag by lazy { javaClass.simpleName }
    private var server: M3u8HttpServer? = null

    @Synchronized
    fun startServer(port: Int = 0) {
        if (server != null) return
        try {
            server = M3u8HttpServer(client, port).also { it.start() }
        } catch (e: Exception) {
            Log.e(tag, "Failed to start server: ${e.message}", e)
            server = null
            throw e
        }
    }

    @Synchronized
    fun stopServer() {
        server?.stop()
        server = null
    }

    fun isRunning(): Boolean = server?.isRunning() ?: false
    fun getServerUrl(): String? = server?.let { "http://localhost:${it.port}" }
    fun processM3u8Url(m3u8Url: String): String? = server?.createLocalUrl(m3u8Url)
    suspend fun processSegmentUrl(segmentUrl: String, headers: Map<String, String> = emptyMap()): ByteArray? = server?.processSegmentUrl(segmentUrl, headers)
    fun getServerInfo(): String = if (isRunning()) "M3U8 HTTP Server is running\nBase URL: ${getServerUrl()}\nStatus: ${server?.getHealthStatus()}" else "M3U8 HTTP Server is not running"
}
