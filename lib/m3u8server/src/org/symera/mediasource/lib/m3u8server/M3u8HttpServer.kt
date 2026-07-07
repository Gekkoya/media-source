package org.symera.mediasource.lib.m3u8server

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.nanohttpd.protocols.http.IHTTPSession
import org.nanohttpd.protocols.http.NanoHTTPD
import org.nanohttpd.protocols.http.response.Response
import org.nanohttpd.protocols.http.response.Response.newChunkedResponse
import org.nanohttpd.protocols.http.response.Response.newFixedLengthResponse
import org.nanohttpd.protocols.http.response.Status
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder

class M3u8HttpServer(private val client: OkHttpClient, port: Int = 0) : NanoHTTPD(port) {
    val port: Int get() = super.getListeningPort()
    private val tag by lazy { javaClass.simpleName }

    @Volatile
    private var running = false

    override fun start() {
        super.start()
        running = true
        Log.d(tag, "M3U8 HTTP Server started on port $port")
    }

    override fun stop() {
        super.stop()
        running = false
        Log.d(tag, "M3U8 HTTP Server stopped")
    }

    fun isRunning(): Boolean = running

    override fun handle(session: IHTTPSession): Response = when {
        session.uri.startsWith("/m3u8") -> handleM3u8Request(session)
        session.uri.startsWith("/segment") -> handleSegmentRequest(session)
        session.uri.startsWith("/health") -> newFixedLengthResponse(Status.OK, MIME_PLAINTEXT, getHealthStatus())
        else -> newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
    }

    private fun handleM3u8Request(session: IHTTPSession): Response {
        val url = session.parameters["url"]?.first()
        if (url.isNullOrBlank()) return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing url parameter")
        return try {
            newFixedLengthResponse(Status.OK, "application/vnd.apple.mpegurl", runBlocking { processM3u8Content(url, extractHeadersFromSession(session)) })
        } catch (e: Exception) {
            Log.e(tag, "Error processing M3U8: ${e.message}", e)
            newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private fun handleSegmentRequest(session: IHTTPSession): Response {
        val url = session.parameters["url"]?.first()
        if (url.isNullOrBlank()) return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing url parameter")
        return try {
            newChunkedResponse(Status.OK, "video/mp2t", ByteArrayInputStream(runBlocking { processSegmentUrl(url, extractHeadersFromSession(session)) }))
        } catch (e: Exception) {
            Log.e(tag, "Error processing segment: ${e.message}", e)
            newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private fun extractHeadersFromSession(session: IHTTPSession): Map<String, String> = buildMap {
        session.headers.forEach { (key, value) ->
            when (key.lowercase()) {
                "user-agent", "referer", "origin", "accept", "accept-language", "accept-encoding", "connection", "cache-control", "pragma" -> put(key, value)
            }
        }
    }

    private suspend fun processM3u8Content(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        modifyM3u8Content(fetchM3u8Content(url, headers), url, port)
    }

    suspend fun processSegmentUrl(url: String, headers: Map<String, String> = emptyMap()): ByteArray = withContext(Dispatchers.IO) {
        fetchSegmentWithAutoDetection(url, headers)
    }

    fun getHealthStatus(): String = if (running) "M3U8 HTTP Server is running on port $port" else "M3U8 HTTP Server is not running"

    private fun request(url: String, headers: Map<String, String>): Request = Request.Builder().url(url).apply {
        headers.forEach { (key, value) -> addHeader(key, value) }
    }.build()

    private fun fetchM3u8Content(url: String, headers: Map<String, String>): String = client.newCall(request(url, headers)).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Failed to fetch m3u8: ${response.code}")
        response.body.string().ifBlank { throw IOException("Empty response body") }
    }

    private fun fetchSegmentWithAutoDetection(url: String, headers: Map<String, String>): ByteArray = client.newCall(request(url, headers)).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Failed to fetch segment: ${response.code}")
        val inputStream = response.body.byteStream()
        val outputStream = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        val bytesRead = inputStream.read(buffer)
        if (bytesRead > 0) {
            val skipBytes = AutoDetector.detectSkipBytes(buffer.copyOf(bytesRead))
            outputStream.write(buffer, skipBytes, bytesRead - skipBytes)
            inputStream.copyTo(outputStream)
        }
        outputStream.toByteArray()
    }

    fun createLocalUrl(m3u8Url: String): String = "http://localhost:$port/m3u8?url=${URLEncoder.encode(m3u8Url, Charsets.UTF_8.name())}"

    private fun modifyM3u8Content(content: String, originalUrl: String, serverPort: Int): String {
        val baseHttpUrl = originalUrl.toHttpUrlOrNull()
        return content.lines().joinToString("\n") { line ->
            if (line.isNotBlank() && !line.startsWith("#")) {
                val resolvedUrl = baseHttpUrl?.resolve(line)?.toString() ?: line
                "http://localhost:$serverPort/segment?url=${URLEncoder.encode(resolvedUrl, Charsets.UTF_8.name())}"
            } else {
                line
            }
        }
    }
}
