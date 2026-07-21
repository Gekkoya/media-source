package org.symera.mediasource.lib.streamwish

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.symera.mediasource.core.UrlUtils
import org.symera.mediasource.core.bodyString
import org.symera.mediasource.core.useAsJsoup
import org.symera.mediasource.lib.playlistutils.PlaylistUtils
import org.symera.mediasource.lib.synchrony.Deobfuscator
import org.symera.mediasource.lib.unpacker.JsUnpacker
import org.symera.source.model.MediaRequest
import org.symera.source.model.SStream
import org.symera.source.model.SubtitleTrack
import org.symera.source.network.awaitSuccess
import org.symera.source.online.GET

class StreamWishExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }
    private val dmcaServersRegex = """dmca\s*=\s*\[(.*?)]""".toRegex(RegexOption.DOT_MATCHES_ALL)
    private val mainServersRegex = """main\s*=\s*\[(.*?)]""".toRegex(RegexOption.DOT_MATCHES_ALL)
    private val rulesServersRegex = """rules\s*=\s*\[(.*?)]""".toRegex(RegexOption.DOT_MATCHES_ALL)

    suspend fun streamsFromUrl(url: String, prefix: String) = streamsFromUrl(url) { "$prefix - $it" }

    suspend fun streamsFromUrl(url: String, videoNameGen: (String) -> String = { quality -> "StreamWish - $quality" }): List<SStream> {
        val embedUrl = getEmbedUrl(url).toHttpUrl()
        val id = getEmbedId(url)
        val isAbsoluteId = id.startsWith("https://") || id.startsWith("http://")
        val domainsToTry = if (isAbsoluteId) listOf("") else DOMAINS

        for (domain in domainsToTry) {
            val fullUrl = UrlUtils.fixUrl(id, "https://$domain") ?: continue
            try {
                val response = client.awaitSuccess(GET(fullUrl, headers))
                val body = response.bodyString()
                if (body.isBlank()) continue
                var doc = Jsoup.parse(body)

                val scriptElement = doc.selectFirst("body > script[src*=/main.js]")
                if (scriptElement != null) {
                    val scriptContent = client.awaitSuccess(GET(scriptElement.absUrl("src"), headers)).bodyString()
                    val deobfuscatedScript = Deobfuscator.deobfuscateScript(scriptContent) ?: continue
                    val dmcaServers = extractServerList(dmcaServersRegex, deobfuscatedScript)
                    val mainServers = extractServerList(mainServersRegex, deobfuscatedScript)
                    val rulesServers = extractServerList(rulesServersRegex, deobfuscatedScript)
                    val destination = (if (embedUrl.host in rulesServers) mainServers.randomOrNull() else dmcaServers.randomOrNull()) ?: continue
                    val redirectedUrl = embedUrl.newBuilder().host(destination).build().toString()
                    doc = client.awaitSuccess(GET(getEmbedUrl(redirectedUrl), headers)).useAsJsoup()
                }

                val scriptBody = doc.selectFirst("script:containsData(m3u8)")?.data()?.let { script ->
                    if (script.contains("eval(function(p,a,c")) JsUnpacker.unpackAndCombine(script) else script
                }
                val masterUrl = scriptBody?.let { M3U8_REGEX.find(it)?.value }
                if (masterUrl != null) {
                    val subtitleList = extractSubtitles(scriptBody)
                    return playlistUtils.extractFromHls(
                        playlistUrl = masterUrl,
                        referer = masterUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}/" } ?: "https://${url.toHttpUrl().host}/",
                        videoNameGen = videoNameGen,
                        subtitleList = playlistUtils.fixSubtitles(subtitleList),
                    )
                }
            } catch (_: Exception) {
                if (isAbsoluteId) return emptyList()
            }
        }
        return emptyList()
    }

    suspend fun videosFromUrl(url: String, prefix: String): List<SStream> = streamsFromUrl(url, prefix)

    suspend fun videosFromUrl(url: String, videoNameGen: (String) -> String = { quality -> "StreamWish - $quality" }): List<SStream> = streamsFromUrl(url, videoNameGen)

    private fun extractServerList(regex: Regex, script: String): List<String> = regex.find(script)?.groupValues?.get(1)
        ?.split(",")?.map { it.trim().removeSurrounding("\"").removeSurrounding("'") }?.filter { it.isNotEmpty() } ?: emptyList()

    private fun getEmbedUrl(url: String): String = if (url.contains("/f/")) "https://streamwish.com/${url.substringAfter("/f/")}" else url

    private fun getEmbedId(url: String): String = Regex(""".*/[efd]/([a-zA-Z0-9]+)""").find(url)?.groupValues?.get(1) ?: url

    private fun extractSubtitles(script: String): List<SubtitleTrack> = try {
        val subtitleStr = script.substringAfter("tracks").substringAfter("[").substringBefore("]")
        val fixedSubtitleStr = FIX_TRACKS_REGEX.replace(subtitleStr) { "\"${it.value}\"" }
        json.decodeFromString<List<TrackDto>>("[$fixedSubtitleStr]")
            .filter { it.kind.equals("captions", true) }
            .map { SubtitleTrack(id = it.file, request = MediaRequest(uri = it.file), language = it.label ?: "") }
    } catch (_: SerializationException) {
        emptyList()
    }

    @Serializable
    private data class TrackDto(val file: String, val kind: String, val label: String? = null)

    companion object {
        private val M3U8_REGEX by lazy { Regex("""https[^"]*m3u8[^"]*""") }
        private val FIX_TRACKS_REGEX by lazy { Regex("""(?<!")(file|kind|label)(?!")""") }
        private val DOMAINS = listOf("streamwish.com", "niramirus.com", "medixiru.com")
    }
}
