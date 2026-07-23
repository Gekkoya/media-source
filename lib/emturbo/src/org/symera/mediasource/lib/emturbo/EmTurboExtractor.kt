package org.symera.mediasource.lib.emturbo

import android.util.Log
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.symera.mediasource.lib.playlistutils.PlaylistUtils
import org.symera.source.model.SStream
import org.symera.source.network.awaitSuccess
import org.symera.source.online.GET

class EmTurboExtractor(
    private val client: OkHttpClient,
    private val headers: Headers = Headers.headersOf(),
) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    suspend fun streamsFromUrl(url: String, prefix: String = ""): List<SStream> = runCatching {
        val script = client.awaitSuccess(GET(url, headers)).use { response ->
            response.body.string()
        }
        val hlsUrl = URL_PLAY_REGEX.find(script)?.groupValues?.get(1)
            ?: run {
                Log.w("EmTurboExtractor", "No urlPlay url=$url")
                return emptyList()
            }
        playlistUtils.extractFromHls(hlsUrl, url) { quality -> "$prefix EmTurbo: $quality" }
    }.onFailure { error ->
        Log.w("EmTurboExtractor", "Failed url=$url message=${error.message}")
    }.getOrDefault(emptyList())

    private companion object {
        val URL_PLAY_REGEX = Regex("""urlPlay\s*=\s*'([^']+)""")
    }
}
