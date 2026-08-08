package org.symera.mediasource.multisrc.pelisplus

import android.util.Log
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.symera.mediasource.core.useAsJsoup
import org.symera.mediasource.lib.burstcloud.BurstCloudExtractor
import org.symera.mediasource.lib.byse.ByseExtractor
import org.symera.mediasource.lib.dood.DoodExtractor
import org.symera.mediasource.lib.emturbo.EmTurboExtractor
import org.symera.mediasource.lib.fastream.FastreamExtractor
import org.symera.mediasource.lib.filemoon.FilemoonExtractor
import org.symera.mediasource.lib.lulu.LuluExtractor
import org.symera.mediasource.lib.mixdrop.MixDropExtractor
import org.symera.mediasource.lib.mp4upload.Mp4uploadExtractor
import org.symera.mediasource.lib.okru.OkruExtractor
import org.symera.mediasource.lib.streamlare.StreamlareExtractor
import org.symera.mediasource.lib.streamsb.StreamSbExtractor
import org.symera.mediasource.lib.streamsilk.StreamSilkExtractor
import org.symera.mediasource.lib.streamtape.StreamTapeExtractor
import org.symera.mediasource.lib.streamwish.StreamWishExtractor
import org.symera.mediasource.lib.universal.UniversalExtractor
import org.symera.mediasource.lib.upstream.UpstreamExtractor
import org.symera.mediasource.lib.uqload.UqloadExtractor
import org.symera.mediasource.lib.vidguard.VidGuardExtractor
import org.symera.mediasource.lib.vidhide.VidHideExtractor
import org.symera.mediasource.lib.voe.VoeExtractor
import org.symera.mediasource.lib.vudeo.VudeoExtractor
import org.symera.mediasource.lib.yourupload.YourUploadExtractor
import org.symera.source.ConfigurableSymeraSource
import org.symera.source.SourceEnvironment
import org.symera.source.model.ContentType
import org.symera.source.model.MediaRequest
import org.symera.source.model.PlayableStream
import org.symera.source.model.SStream
import org.symera.source.model.SourcePreference
import org.symera.source.network.awaitSuccess
import org.symera.source.online.GET
import org.symera.source.online.SymeraHttpSource
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

abstract class PelisPlus(
    environment: SourceEnvironment,
) : SymeraHttpSource(environment),
    ConfigurableSymeraSource {

    override val lang = "es"
    override val contentTypes = setOf(ContentType.MOVIE, ContentType.SERIES)

    protected open val json: Json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val okruExtractor by lazy { OkruExtractor(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client, environment.mediaBrowserFactory) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val vudeoExtractor by lazy { VudeoExtractor(client, headers) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamlareExtractor by lazy { StreamlareExtractor(client) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val burstCloudExtractor by lazy { BurstCloudExtractor(client) }
    private val fastreamExtractor by lazy { FastreamExtractor(client, headers) }
    private val upstreamExtractor by lazy { UpstreamExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val streamSilkExtractor by lazy { StreamSilkExtractor(client) }
    private val vidGuardExtractor by lazy { VidGuardExtractor(client) }
    private val byseExtractor by lazy { ByseExtractor(client, headers, json) }
    private val emTurboExtractor by lazy { EmTurboExtractor(client, headers) }
    private val luluExtractor by lazy { LuluExtractor(client, headers) }
    private val universalExtractor by lazy { UniversalExtractor(client, environment.mediaBrowserFactory) }
    private val streamSbExtractor by lazy { StreamSbExtractor(client, headers) }

    /**
     * Keep this sequential in callers when UniversalExtractor is possible; it may need WebView.
     */
    protected suspend fun serverStreamResolver(url: String, prefix: String = "", serverName: String? = ""): List<SStream> {
        val source = serverName?.ifEmpty { url } ?: url
        val matched = routeKey(source)
        val streams = when (matched) {
            "voe" -> voeExtractor.streamsFromUrl(url, "$prefix ")
            "okru" -> resolveHttpThenBrowser(
                http = { okruExtractor.streamsFromUrl(url, prefix) },
                browser = { browserStreams(url, prefix) },
            )
            "filemoon" -> filemoonExtractor.streamsFromUrl(url, prefix = "$prefix Filemoon:")
            "mixdrop" -> resolveHttpThenBrowser(
                http = { mixDropExtractor.streamsFromUrl(url, prefix = "$prefix ") },
                browser = { browserStreams(url, prefix) },
            )
            "amazon" -> amazonStreamsFromUrl(url, prefix)
            "uqload" -> uqloadExtractor.streamsFromUrl(url, "$prefix ")
            "vudeo" -> resolveVudeoStreams(
                http = { vudeoExtractor.streamsFromUrl(url, "$prefix ") },
                browser = { browserStreams(url, "$prefix ") },
            )
            "mp4upload" -> mp4uploadExtractor.streamsFromUrl(url, headers, prefix = "$prefix ")
            "streamwish" -> streamWishExtractor.streamsFromUrl(url) { "$prefix StreamWish:$it" }
            "doodstream" -> doodExtractor.streamsFromUrl(url, "$prefix DoodStream")
            "streamlare" -> streamlareExtractor.streamsFromUrl(url, prefix)
            "yourupload" -> yourUploadExtractor.streamFromUrl(url, headers = headers, prefix = "$prefix ")
            "burstcloud" -> burstCloudExtractor.streamFromUrl(url, headers = headers, prefix = "$prefix ")
            "fastream" -> fastreamExtractor.streamsFromUrl(url, prefix = "$prefix Fastream:")
            "upstream" -> upstreamExtractor.streamsFromUrl(url, prefix = "$prefix ")
            "streamsilk" -> streamSilkExtractor.streamsFromUrl(url) { "$prefix StreamSilk:$it" }
            "streamtape" -> streamTapeExtractor.streamsFromUrl(url, quality = "$prefix StreamTape")
            "vidhide" -> vidHideExtractor.streamsFromUrl(url) { "$prefix - VidHide:$it" }
            "vidguard" -> vidGuardExtractor.streamsFromUrl(url, prefix = "$prefix ")
            "byse" -> byseExtractor.streamsFromUrl(url, prefix).ifEmpty {
                // Byse playback API now requires browser fingerprint attestation.
                // Reuse real WebView networking as fallback, preserving its cookies and headers.
                val embedUrl = byseExtractor.embedUrlFromUrl(url) ?: url
                browserStreams(embedUrl, "$prefix Byse:")
            }
            "emturbo" -> emTurboExtractor.streamsFromUrl(url, prefix)
            "lulu" -> luluExtractor.streamsFromUrl(url, prefix)
            "streamsb" -> streamSbExtractor.streamsFromUrl(url, prefix)
            else -> browserStreams(url, "$prefix ")
        }
        Log.d("SymeraHoster", "host=${url.safeHost()} streams=${streams.size}")
        return streams
    }

    private suspend fun browserStreams(url: String, prefix: String): List<SStream> = universalExtractor.streamsFromUrl(
        origRequestUrl = url,
        origRequestHeader = headers,
        prefix = prefix,
        allowedTopLevelHosts = browserAllowedTopLevelHosts(url),
    )

    private fun browserAllowedTopLevelHosts(url: String): Set<String> = setOf(
        baseUrl.toHttpUrl().host,
        url.toHttpUrl().host,
    )

    protected suspend fun serverVideoResolver(url: String, prefix: String = "", serverName: String? = ""): List<SStream> = serverStreamResolver(url, prefix, serverName)

    private suspend fun amazonStreamsFromUrl(url: String, prefix: String): List<SStream> {
        val body = client.awaitSuccess(GET(url)).useAsJsoup()
        val shareId = body.selectFirst("script:containsData(var shareId)")?.data()
            ?.substringAfter("shareId = \"")
            ?.substringBefore("\"")
            ?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        val amazonApiJson = client.awaitSuccess(GET("https://www.amazon.com/drive/v1/shares/$shareId?resourceVersion=V2&ContentType=JSON&asset=ALL"))
            .useAsJsoup()
        val epId = amazonApiJson.toString().substringAfter("\"id\":\"").substringBefore("\"")
        val amazonApi = client.awaitSuccess(GET("https://www.amazon.com/drive/v1/nodes/$epId/children?resourceVersion=V2&ContentType=JSON&limit=200&sort=%5B%22kind+DESC%22%2C+%22modifiedDate+DESC%22%5D&asset=ALL&tempLink=true&shareId=$shareId"))
            .useAsJsoup()
        val videoUrl = amazonApi.toString().substringAfter("\"FOLDER\":").substringAfter("tempLink\":\"").substringBefore("\"")
        return listOf(PlayableStream(id = amazonStreamId(epId), title = "$prefix Amazon", request = MediaRequest(uri = videoUrl)))
    }

    protected fun fetchUrls(text: String?): List<String> {
        if (text.isNullOrEmpty()) return emptyList()
        return REGEX_LINK.findAll(text).map { it.value.trim().removeSurrounding("\"") }.toList()
    }

    protected fun String.getLang(): String = when {
        arrayOf("0", "lat").any(this) -> "[LAT]"
        arrayOf("1", "cast").any(this) -> "[CAST]"
        arrayOf("2", "eng", "sub").any(this) -> "[SUB]"
        else -> ""
    }

    private fun Array<String>.any(url: String): Boolean = any { url.contains(it, ignoreCase = true) }

    override fun List<SStream>.sortStreams(): List<SStream> {
        val preferences = environment.preferencesFor(sourcePreferenceNamespace)
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)
        return sortedWith(
            compareBy(
                { it.title.orEmpty().contains(server, true) },
                { it.title.orEmpty().contains(quality) },
                { Regex("""(\d+)p""").find(it.title.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun getSourcePreferences(): List<SourcePreference<*>> = listOf(
        SourcePreference.Select(
            key = PREF_SERVER_KEY,
            title = "Preferred server",
            values = SERVER_LIST.map { SourcePreference.Option(it) },
            summary = "%s",
            defaultValue = PREF_SERVER_DEFAULT,
        ),
        SourcePreference.Select(
            key = PREF_QUALITY_KEY,
            title = "Preferred quality",
            values = QUALITY_LIST.map { SourcePreference.Option(it) },
            summary = "%s",
            defaultValue = PREF_QUALITY_DEFAULT,
        ),
    )

    companion object {
        val REGEX_LINK = """https?://(www\.)?[-a-zA-Z0-9@:%._+~#=]{1,256}\.[a-zA-Z0-9()]{1,6}\b([-a-zA-Z0-9()@:%_+.~#?&/=]*)""".toRegex()

        const val PREF_SERVER_KEY = "preferred_server"
        const val PREF_SERVER_DEFAULT = "VidHide"
        val SERVER_LIST = listOf(
            "YourUpload",
            "BurstCloud",
            "Voe",
            "Mp4Upload",
            "Doodstream",
            "Upload",
            "Upstream",
            "StreamTape",
            "Amazon",
            "Fastream",
            "Filemoon",
            "StreamWish",
            "Okru",
            "Streamlare",
            "VidGuard",
            "VidHide",
            "StreamHide",
            "Tomatomatela",
        )

        const val PREF_QUALITY_KEY = "preferred_quality"
        const val PREF_QUALITY_DEFAULT = "1080"
        val QUALITY_LIST = listOf("1080", "720", "480", "360")

        private val conventions = listOf(
            "voe" to listOf("voe", "tubelessceliolymph", "simpulumlamerop", "urochsunloath", "nathanfromsubject", "yip.", "metagnathtuggers", "donaldlineelse"),
            "okru" to listOf("ok.ru", "okru"),
            "filemoon" to listOf("filemoon", "moonplayer", "moviesm4u", "files.im"),
            "mixdrop" to listOf("mixdrop", "mixdroop", "mxdrop"),
            "amazon" to listOf("amazon", "amz"),
            "uqload" to listOf("uqload"),
            "vudeo" to listOf("vudeo", "vudea"),
            "mp4upload" to listOf("mp4upload"),
            "streamwish" to listOf("wishembed", "streamwish", "flaswish", "strwish", "wish", "Kswplayer", "Swhoi", "Multimovies", "Uqloads", "neko-stream", "swdyu", "iplayerhls", "streamgg"),
            "doodstream" to listOf("doodstream", "dood.", "ds2play", "doods.", "ds2video", "dooood", "d000d", "d0000d"),
            "streamlare" to listOf("streamlare", "slmaxed"),
            "yourupload" to listOf("yourupload", "upload"),
            "burstcloud" to listOf("burstcloud", "burst"),
            "fastream" to listOf("fastream"),
            "upstream" to listOf("upstream"),
            "streamsilk" to listOf("streamsilk"),
            "streamtape" to listOf("streamtape", "stp", "stape", "shavetape"),
            "vidhide" to listOf("ahvsh", "streamhide", "guccihide", "streamvid", "vidhide", "kinoger", "smoothpre", "dhtpre", "peytonepre", "earnvids", "ryderjet"),
            "vidguard" to listOf("vembed", "guard", "listeamed", "bembed", "vgfplay"),
            "byse" to listOf("byse", "bysekoze", "bysefujedu"),
            "emturbo" to listOf("emturbo", "emturbovid", "lvturbo", "sblanh"),
            "lulu" to listOf("lulu", "luluvdo"),
            "streamsb" to listOf("streamsb", "playersb", "sbplay", "streamssb"),
            "universal" to listOf("pelisplus-cdn", "primeload", "waaw", "rpmstream", "pelisplus.rpmstream"),
        )

        fun routeKey(source: String): String? = conventions.firstOrNull { (_, names) ->
            names.any { it.lowercase() in source.lowercase() }
        }?.first

        fun amazonStreamId(epId: String): String = "amazon-${sha256(epId)}"

        private fun String.safeHost(): String = runCatching { toHttpUrl().host }.getOrDefault("unknown")

        internal suspend fun resolveVudeoStreams(
            http: suspend () -> List<SStream>,
            browser: suspend () -> List<SStream>,
        ): List<SStream> {
            val httpStreams = try {
                http()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                emptyList()
            }
            return httpStreams.ifEmpty { browser() }
        }

        internal suspend fun resolveHttpThenBrowser(
            http: suspend () -> List<SStream>,
            browser: suspend () -> List<SStream>,
        ): List<SStream> = resolveVudeoStreams(http, browser)

        private fun sha256(value: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
    }
}
