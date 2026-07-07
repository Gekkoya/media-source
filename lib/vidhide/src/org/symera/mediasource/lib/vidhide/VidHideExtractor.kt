package org.symera.mediasource.lib.vidhide

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.symera.mediasource.core.UrlUtils
import org.symera.mediasource.core.parallelCatchingFlatMap
import org.symera.mediasource.core.useAsJsoup
import org.symera.mediasource.lib.playlistutils.PlaylistUtils
import org.symera.mediasource.lib.unpacker.autoUnpacker
import org.symera.source.model.SStream
import org.symera.source.model.SubtitleTrack
import org.symera.source.online.GET
import org.symera.source.online.awaitSuccess

class VidHideExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    fun streamsFromUrl(url: String, videoNameGen: (String) -> String = { quality -> "VidHide - $quality" }): List<SStream> = runBlocking {
        val script = fetchAndExtractScript(url) ?: return@runBlocking emptyList()
        val playlists = extractVideoUrl(script, url)
        val subtitleList = extractSubtitles(script, url)

        playlists.parallelCatchingFlatMap { videoUrl ->
            playlistUtils.extractFromHls(
                videoUrl,
                referer = url,
                videoNameGen = videoNameGen,
                subtitleList = subtitleList,
            )
        }
    }

    private suspend fun fetchAndExtractScript(url: String): String? = client.newCall(GET(url, headers)).awaitSuccess()
        .useAsJsoup()
        .select("script")
        .find { it.html().contains("eval(function(p,a,c,k,e,d)") }
        ?.html()
        ?.let(::autoUnpacker)

    private fun extractVideoUrl(script: String, baseUrl: String): List<String> = sourceRegex
        .findAll(script).mapNotNull { UrlUtils.fixUrl(it.groupValues[1], baseUrl) }.toList()

    private fun extractSubtitles(script: String, baseUrl: String): List<SubtitleTrack> = try {
        val subtitleStr = script.substringAfter("tracks").substringAfter("[").substringBefore("]")
        json.decodeFromString<List<TrackDto>>("[$subtitleStr]")
            .filter { it.kind.equals("captions", true) }
            .mapNotNull {
                UrlUtils.fixUrl(it.file, baseUrl)?.let { url -> SubtitleTrack(url, it.label ?: "") }
            }
    } catch (_: SerializationException) {
        emptyList()
    }

    @Serializable
    private data class TrackDto(
        val file: String,
        val kind: String,
        val label: String? = null,
    )

    companion object {
        private val sourceRegex = Regex(""""((?:https?:/)?/[^"]*m3u8[^"]*)"""")
    }
}
