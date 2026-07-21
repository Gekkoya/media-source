package org.symera.mediasource.multisrc.pelisplus

import kotlinx.serialization.json.Json
import org.symera.mediasource.core.useAsJsoup
import org.symera.mediasource.lib.burstcloud.BurstCloudExtractor
import org.symera.mediasource.lib.dood.DoodExtractor
import org.symera.mediasource.lib.fastream.FastreamExtractor
import org.symera.mediasource.lib.filemoon.FilemoonExtractor
import org.symera.mediasource.lib.mp4upload.Mp4uploadExtractor
import org.symera.mediasource.lib.okru.OkruExtractor
import org.symera.mediasource.lib.streamlare.StreamlareExtractor
import org.symera.mediasource.lib.streamsilk.StreamSilkExtractor
import org.symera.mediasource.lib.streamtape.StreamTapeExtractor
import org.symera.mediasource.lib.streamwish.StreamWishExtractor
import org.symera.mediasource.lib.universal.UniversalExtractor
import org.symera.mediasource.lib.upstream.UpstreamExtractor
import org.symera.mediasource.lib.uqload.UqloadExtractor
import org.symera.mediasource.lib.vidguard.VidGuardExtractor
import org.symera.mediasource.lib.vidhide.VidHideExtractor
import org.symera.mediasource.lib.voe.VoeExtractor
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
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
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
    private val universalExtractor by lazy { UniversalExtractor(client) }

    /**
     * Keep this sequential in callers when UniversalExtractor is possible; it may need WebView.
     */
    protected suspend fun serverStreamResolver(url: String, prefix: String = "", serverName: String? = ""): List<SStream> {
        val source = serverName?.ifEmpty { url } ?: url
        val matched = conventions.firstOrNull { (_, names) -> names.any { it.lowercase() in source.lowercase() } }?.first
        return when (matched) {
            "voe" -> voeExtractor.streamsFromUrl(url, "$prefix ")
            "okru" -> okruExtractor.streamsFromUrl(url, prefix)
            "filemoon" -> filemoonExtractor.streamsFromUrl(url, prefix = "$prefix Filemoon:")
            "amazon" -> amazonStreamsFromUrl(url, prefix)
            "uqload" -> uqloadExtractor.streamsFromUrl(url, "$prefix ")
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
            else -> universalExtractor.streamsFromUrl(url, headers, prefix = "$prefix ")
        }
    }

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
        return listOf(PlayableStream(id = videoUrl, title = "$prefix Amazon", request = MediaRequest(uri = videoUrl)))
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
            "amazon" to listOf("amazon", "amz"),
            "uqload" to listOf("uqload"),
            "mp4upload" to listOf("mp4upload"),
            "streamwish" to listOf("wishembed", "streamwish", "strwish", "wish", "Kswplayer", "Swhoi", "Multimovies", "Uqloads", "neko-stream", "swdyu", "iplayerhls", "streamgg"),
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
        )
    }
}
