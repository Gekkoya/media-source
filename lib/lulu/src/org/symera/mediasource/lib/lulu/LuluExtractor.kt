package org.symera.mediasource.lib.lulu

import android.util.Log
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.symera.mediasource.lib.playlistutils.PlaylistUtils
import org.symera.source.model.SStream
import org.symera.source.network.awaitSuccess
import org.symera.source.online.GET

class LuluExtractor(
    private val client: OkHttpClient,
    private val headers: Headers = Headers.headersOf(),
) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val requestHeaders = headers.newBuilder()
        .add("Referer", "https://luluvdo.com/")
        .add("Origin", "https://luluvdo.com")
        .build()

    suspend fun streamsFromUrl(url: String, prefix: String = ""): List<SStream> = runCatching {
        val html = client.awaitSuccess(GET(url, requestHeaders)).use { it.body.string() }
        val script = html.substringAfter("eval(function(p,a,c,k,e,d)", "")
        val unpacked = unpack(script)
        val hlsUrl = SOURCE_REGEX.find(unpacked)?.groupValues?.get(1)
            ?: SOURCE_REGEX.find(html)?.groupValues?.get(1)
            ?: return emptyList()
        playlistUtils.extractFromHls(hlsUrl.replace("\\/", "/"), url) { quality -> "$prefix Lulu: $quality" }
    }.onFailure { error ->
        Log.w("LuluExtractor", "Failed url=$url message=${error.message}")
    }.getOrDefault(emptyList())

    private fun unpack(script: String): String {
        val match = PACKED_REGEX.find(script) ?: return script
        val packed = match.groupValues[1]
        val radix = match.groupValues[2].toInt()
        val count = match.groupValues[3].toInt()
        val words = match.groupValues[4].split("|")
        var result = packed
        for (index in (count - 1) downTo 0) {
            val word = words.getOrNull(index).orEmpty()
            if (word.isNotEmpty()) result = result.replace("\\b${toRadix(index, radix)}\\b".toRegex(), word)
        }
        return result.replace("\\'", "'").replace("\\/", "/")
    }

    private fun toRadix(value: Int, radix: Int): String {
        val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
        if (value == 0) return "0"
        var current = value
        return buildString {
            while (current > 0) {
                val remainder = current % radix
                insert(0, chars[remainder])
                current /= radix
            }
        }
    }

    private companion object {
        val PACKED_REGEX = Regex(
            """\}\('(.+?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)\)\)""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        val SOURCE_REGEX = Regex("""(?:file|url)\s*:\s*[\"']([^\"']+\.m3u8[^\"']*)[\"']""")
    }
}
