package org.symera.mediasource.es.jkanime

import android.util.Base64
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.symera.mediasource.core.bodyString
import org.symera.mediasource.core.parseAs
import org.symera.mediasource.es.jkanime.extractors.JkanimeExtractor
import org.symera.mediasource.lib.dood.DoodExtractor
import org.symera.mediasource.lib.filemoon.FilemoonExtractor
import org.symera.mediasource.lib.mixdrop.MixDropExtractor
import org.symera.mediasource.lib.mp4upload.Mp4uploadExtractor
import org.symera.mediasource.lib.okru.OkruExtractor
import org.symera.mediasource.lib.streamtape.StreamTapeExtractor
import org.symera.mediasource.lib.universal.UniversalExtractor
import org.symera.mediasource.lib.vidhide.VidHideExtractor
import org.symera.mediasource.lib.voe.VoeExtractor
import org.symera.source.ConfigurableSymeraSource
import org.symera.source.model.ContentPage
import org.symera.source.model.ContentStatus
import org.symera.source.model.ContentType
import org.symera.source.model.Filter
import org.symera.source.model.FilterList
import org.symera.source.model.SContent
import org.symera.source.model.SHoster
import org.symera.source.model.SPlayableItem
import org.symera.source.model.SStream
import org.symera.source.model.SourcePreference
import org.symera.source.online.GET
import org.symera.source.online.POST
import org.symera.source.online.SymeraHttpSource
import org.symera.source.online.asJsoup
import org.symera.source.preferenceValues
import java.text.SimpleDateFormat
import java.util.Locale

class Jkanime :
    SymeraHttpSource(),
    ConfigurableSymeraSource {
    override val name = "Jkanime"
    override val baseUrl = "https://jkanime.net"
    override val lang = "es"
    override val contentTypes = setOf(ContentType.ANIME, ContentType.SERIES, ContentType.MOVIE)

    private val baseClient: OkHttpClient by lazy { defaultClientProvider() }

    private val noRedirectClient: OkHttpClient by lazy {
        baseClient.newBuilder()
            .followRedirects(false)
            .addInterceptor { chain ->
                val request = chain.request()
                val response = chain.proceed(request)
                if (response.isRedirect) {
                    val location = response.header("Location") ?: return@addInterceptor response
                    val originalParams = request.url.queryParameterNames.associateWith { request.url.queryParameter(it) }
                    val redirectUrl = location.toHttpUrl().newBuilder().apply {
                        originalParams.forEach { (key, value) -> if (value != null) addQueryParameter(key, value) }
                    }.build()
                    val newRequest = request.newBuilder().url(redirectUrl).build()
                    response.close()
                    return@addInterceptor chain.proceed(newRequest)
                }
                response
            }.build()
    }

    override val client: OkHttpClient by lazy {
        baseClient.newBuilder()
            .addInterceptor { chain ->
                val request = chain.request()
                if (request.url.pathSegments.firstOrNull() == "buscar") {
                    return@addInterceptor noRedirectClient.newCall(request).execute()
                }
                chain.proceed(request)
            }.build()
    }

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    override fun moviesRequest(page: Int): Request = directoryRequest(page, type = "peliculas")

    override fun moviesParse(response: Response): ContentPage = searchParse(response)

    override fun seriesRequest(page: Int): Request = directoryRequest(page, type = "animes")

    override fun seriesParse(response: Response): ContentPage = searchParse(response)

    private fun directoryRequest(page: Int, type: String): Request {
        val url = "$baseUrl/directorio".toHttpUrl().newBuilder()
            .addQueryParameter("tipo", type)
            .addQueryParameter("p", page.toString())
            .build()
        return GET(url.toString(), headers)
    }

    override fun searchRequest(page: Int, query: String, filters: FilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val dayFilter = filterList.find { it is DayFilter } as? DayFilter
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            when {
                dayFilter != null && dayFilter.state != 0 -> {
                    addPathSegment("horario")
                    addPathSegment("")
                    fragment(dayFilter.toValue())
                }
                query.isNotBlank() -> {
                    addPathSegment("buscar")
                    addPathSegment(query.replace(" ", "_"))
                }
                else -> {
                    addPathSegment("directorio")
                    addQueryParameter("p", page.toString())
                    filterList.filterIsInstance<UriPartFilterInterface>()
                        .mapNotNull { it.toQueryParam() }
                        .forEach { (name, value) -> addQueryParameter(name, value) }
                }
            }
        }

        return GET(url.build().toString(), headers)
    }

    override fun searchParse(response: Response): ContentPage {
        val document = response.asJsoup()
        val location = document.location().toHttpUrl().encodedPath
        return when {
            location.startsWith("/directorio") -> searchParseDirectory(document)
            location.startsWith("/buscar") -> searchParseSearch(document)
            location.startsWith("/horario") -> searchParseSchedule(document)
            else -> ContentPage.Empty
        }
    }

    private fun searchParseDirectory(document: Document): ContentPage {
        val animePageJson = document.selectFirst("script:containsData(var animes = )")?.data()
            ?.let { script -> animePagePattern.find(script)?.groups?.get(1)?.value }
            ?.takeIf { it.isNotBlank() }
            ?.let { jsonStr -> json.decodeFromString<AnimePageDto>(jsonStr) }
            ?: return ContentPage.Empty

        val contents = animePageJson.data.map { animeDto ->
            SContent.create().apply {
                setUrlWithoutDomain(animeDto.url)
                title = animeDto.title
                description = animeDto.synopsis
                posterUrl = animeDto.thumbnailUrl
                genres = animeDto.studios?.takeIf(String::isNotBlank)?.let(::listOf)
                status = animeDto.status?.let(::parseStatus) ?: ContentStatus.UNKNOWN
                contentType = animeDto.type?.let(::parseContentType)
            }
        }
        return ContentPage(contents, !animePageJson.nextPageUrl.isNullOrBlank())
    }

    private fun searchParseSearch(document: Document): ContentPage {
        val contents = document.select("div.row div.row.page_directorio div.anime__item")
            .mapNotNull { element ->
                val itemText = element.selectFirst("div.anime__item__text a") ?: return@mapNotNull null
                SContent.create().apply {
                    title = itemText.text()
                    posterUrl = element.selectFirst("div.g-0")?.attr("abs:data-setbg")
                    setUrlWithoutDomain(itemText.attr("href"))
                    contentType = ContentType.ANIME
                }
            }
        return ContentPage(contents, false)
    }

    private fun searchParseSchedule(document: Document): ContentPage {
        val day = document.location().substringAfterLast("#")
        val animeBox = document.selectFirst("h2:contains($day) ~ div.cajas")
        val contents = animeBox?.select("div.boxx")?.mapNotNull { element ->
            SContent.create().apply {
                val url = element.selectFirst("a")?.attr("abs:href")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                setUrlWithoutDomain(url)
                title = element.selectFirst("img")?.attr("title") ?: return@mapNotNull null
                posterUrl = element.selectFirst("img")?.attr("abs:src")
                contentType = ContentType.ANIME
            }
        } ?: emptyList()

        return ContentPage(contents, false)
    }

    override fun contentDetailsParse(response: Response): SContent {
        val document = response.asJsoup()
        return SContent.create().apply {
            document.selectFirst("div.anime__details__content div.anime_pic img")?.attr("abs:src")?.let { posterUrl = it }
            document.selectFirst("div.anime__details__content div.anime_info h3")?.text()?.let { title = it }
            document.selectFirst("div.anime__details__content div.anime_info p.scroll")?.text()?.let { description = it }
            url = response.request.url.encodedPath
            contentType = ContentType.ANIME
            document.select("div.anime__details__content div.anime_data.pc li").forEach { animeData ->
                val data = animeData.select("span").text()
                if (data.contains("Generos:")) {
                    genres = animeData.select("a").map { it.text() }
                }
                if (data.contains("Estado")) {
                    status = parseStatus(animeData.select("div").text())
                }
            }
        }
    }

    override fun playableItemsParse(response: Response): List<SPlayableItem> {
        val animeUrl = response.request.url.toString().trim('/')
        val pageBody = response.asJsoup()
        val token = pageBody.selectFirst("meta[name=csrf-token]")?.attr("content") ?: return emptyList()
        val xsrfToken = response.headers("Set-Cookie")
        val formData = FormBody.Builder().add("_token", token).build()
        val animeId = pageBody.selectFirst("div.anime__details__content div.pc div#guardar-anime")
            ?.attr("data-anime")
            ?.takeIf { it.isNotBlank() } ?: return emptyList()

        val cookieHeaders = headers.newBuilder().apply {
            add("Cookie", xsrfToken.joinToString(" ") { "${it.substringBeforeLast(";")};" })
        }.build()

        val playableItems = buildList {
            var page = 1
            do {
                val episodesPage = fetchAnimeEpisodes(animeId, page, cookieHeaders, formData)
                addAll(episodesPage.data.toPlayableItemList(animeUrl))
                page++
            } while (!episodesPage.nextPageUrl.isNullOrBlank() && page <= episodesPage.lastPage)
        }
        return playableItems.reversed()
    }

    private fun List<EpisodeDto>.toPlayableItemList(animeUrl: String): List<SPlayableItem> = map { episode ->
        SPlayableItem.create().apply {
            episodeNumber = episode.number.toDouble()
            title = "Episodio ${episode.number}"
            airDate = episode.timestamp?.toDate() ?: 0L
            thumbnailUrl = episode.image
            setUrlWithoutDomain("$animeUrl/${episode.number}")
        }
    }

    private fun fetchAnimeEpisodes(animeId: String, currentPage: Int, cookieHeaders: Headers, formData: FormBody): EpisodesPageDto = client.newCall(POST("$baseUrl/ajax/episodes/$animeId/$currentPage", headers = cookieHeaders, body = formData))
        .execute().parseAs(json)

    override fun hostersParse(response: Response): List<SHoster> {
        val document = response.asJsoup()
        return getVideoLinks(document).map { (url, lang, name) ->
            val matched = serverMatching.firstOrNull { (_, names) -> names.any { it.lowercase() in url.lowercase() } }?.first ?: name.lowercase()
            SHoster(
                hosterUrl = url,
                hosterName = matched,
                displayName = listOf(lang, name.ifBlank { matched }).filter { it.isNotBlank() }.joinToString(" "),
                internalData = listOf(lang, name, matched).joinToString("\t"),
                lazy = true,
            )
        }
    }

    override suspend fun getStreams(hoster: SHoster): List<SStream> {
        val parts = hoster.internalData.split('\t', limit = 3)
        val streamLang = parts.getOrNull(0).orEmpty()
        val name = parts.getOrNull(1).orEmpty()
        val matched = parts.getOrNull(2).orEmpty().ifBlank { hoster.hosterName }
        val url = hoster.hosterUrl

        return when (matched) {
            "okru" -> okruExtractor.streamsFromUrl(url, streamLang)
            "voe" -> voeExtractor.streamsFromUrl(url, "$streamLang ")
            "filemoon" -> filemoonExtractor.streamsFromUrl(url, prefix = "$streamLang Filemoon:")
            "streamtape" -> streamTapeExtractor.streamsFromUrl(url, quality = "$streamLang StreamTape")
            "mp4upload" -> mp4uploadExtractor.streamsFromUrl(url, prefix = "$streamLang ", headers = headers)
            "mixdrop" -> mixDropExtractor.streamsFromUrl(url, prefix = "$streamLang ")
            "doostream" -> doodExtractor.streamsFromUrl(url.replace("d-s.io", "dsvplay.com"), "$streamLang ${name.ifBlank { "Doodstream" }}")
            "vidhide" -> vidHideExtractor.streamsFromUrl(url, videoNameGen = { "$streamLang - VidHide:$it" })
            "mediafire" -> jkanimeExtractor.getMediafireFromUrl(url, "$streamLang ")
            "desuka" -> jkanimeExtractor.getDesukaFromUrl(url, "$streamLang ")
            "nozomi" -> jkanimeExtractor.getNozomiFromUrl(url, "$streamLang ")
            "desu" -> jkanimeExtractor.getDesuFromUrl(url, "$streamLang ")
            "magi" -> jkanimeExtractor.getMagiFromUrl(url, "$streamLang ")
            else -> universalExtractor.streamsFromUrl(url, headers, prefix = "$streamLang $name")
        }.sortStreams()
    }

    override fun streamsParse(response: Response, hoster: SHoster): List<SStream> = emptyList()

    private fun getVideoLinks(document: Document): List<Triple<String, String, String>> {
        val scriptServers = document.selectFirst("script:containsData(var video = [];)")?.data() ?: return emptyList()
        val isRemote = scriptServers.contains("= remote+'", true)
        val jsServer = scriptServers.substringAfter("var remote = '").substringBefore("'")
        val jsPath = scriptServers.substringAfter("= remote+'").substringBefore("'")

        val jsLinks = if (isRemote && jsServer.isNotEmpty()) {
            client.newCall(GET(jsServer + jsPath)).execute().bodyString()
        } else {
            val regex = Regex("""var servers\s*=\s*(\[.*]);""", RegexOption.UNIX_LINES)
            regex.find(scriptServers)?.groupValues?.get(1)
        }?.parseAs<Array<JsLinks>>()?.map {
            Triple(String(Base64.decode(it.remote, Base64.DEFAULT)), it.lang.getLang(), it.server ?: "")
        } ?: emptyList()

        val htmlLinks = document.select("div.bg-servers a").map {
            val serverId = it.attr("data-id")
            val linkLang = it.attr("class").substringAfter("lg_").substringBefore(" ").toIntOrNull()?.getLang() ?: ""
            val name = it.text().ifBlank { "" }
            val url = scriptServers
                .substringAfter("video[$serverId] = '<iframe class=\"player_conte\" src=\"")
                .substringBefore("\"")
                .replace("/jkokru.php?u=", "http://ok.ru/videoembed/")
                .replace("/jkvmixdrop.php?u=", "https://mixdrop.ag/e/")
                .replace("/jksw.php?u=", "https://sfastwish.com/e/")
                .replace("/jk.php?u=", "$baseUrl/")
            Triple(url, linkLang, name)
        }

        return jsLinks + htmlLinks
    }

    override fun List<SStream>.sortStreams(): List<SStream> {
        val preferences = preferenceValues()
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)
        val language = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)
        return sortedWith(
            compareBy(
                { it.title.contains(language) },
                { it.title.contains(server, true) },
                { it.title.contains(quality) },
                { Regex("""(\d+)p""").find(it.title)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun getFilterList() = FilterList(
        Filter.Header("La búsqueda por texto no incluye filtros"),
        GenreFilter(),
        LetterFilter(),
        DemographyFilter(),
        CategoryFilter(),
        TypeFilter(),
        StateFilter(),
        Filter.Header("Búsqueda por año"),
        YearFilter(),
        SeasonFilter(),
        Filter.Header("Filtros de ordenamiento"),
        OrderByFilter(),
        SortModifiers(),
        Filter.Separator(),
        DayFilter(),
    )

    override fun getSourcePreferences(): List<SourcePreference<*>> = listOf(
        SourcePreference.Select(
            key = PREF_LANGUAGE_KEY,
            title = "Preferred language",
            values = LANGUAGE_LIST.map { SourcePreference.Option(it) },
            summary = "%s",
            defaultValue = PREF_LANGUAGE_DEFAULT,
        ),
        SourcePreference.Select(
            key = PREF_QUALITY_KEY,
            title = "Preferred quality",
            values = QUALITY_LIST.map { SourcePreference.Option(it) },
            summary = "%s",
            defaultValue = PREF_QUALITY_DEFAULT,
        ),
        SourcePreference.Select(
            key = PREF_SERVER_KEY,
            title = "Preferred server",
            values = SERVER_LIST.map { SourcePreference.Option(it) },
            summary = "%s",
            defaultValue = PREF_SERVER_DEFAULT,
        ),
    )

    private val languages = arrayOf(1 to "[JAP]", 3 to "[LAT]", 4 to "[CHIN]")
    private fun Int?.getLang() = languages.firstOrNull { it.first == this }?.second ?: ""

    private fun parseStatus(statusString: String): ContentStatus = when {
        statusString.contains("Por estrenar", true) -> ContentStatus.ONGOING
        statusString.contains("En emision", true) -> ContentStatus.ONGOING
        statusString.contains("En emisión", true) -> ContentStatus.ONGOING
        statusString.contains("Concluido", true) -> ContentStatus.COMPLETED
        statusString.contains("Finalizado", true) -> ContentStatus.COMPLETED
        else -> ContentStatus.UNKNOWN
    }

    private fun parseContentType(type: String): ContentType = when {
        type.contains("pel", true) -> ContentType.MOVIE
        type.contains("ova", true) || type.contains("ona", true) || type.contains("especial", true) -> ContentType.OTHER
        else -> ContentType.ANIME
    }

    private fun String.toDate(): Long = runCatching { DATE_FORMATTER.parse(trim())?.time }.getOrNull() ?: 0L

    private val okruExtractor by lazy { OkruExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val jkanimeExtractor by lazy { JkanimeExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    companion object {
        private const val PREF_LANGUAGE_KEY = "preferred_language"
        private const val PREF_LANGUAGE_DEFAULT = "[JAP]"
        private val LANGUAGE_LIST = listOf("[JAP]", "[LAT]")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = listOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private val SERVER_LIST = listOf(
            "Okru",
            "Voe",
            "Filemoon",
            "StreamTape",
            "Mp4Upload",
            "Mixdrop",
            "Streamwish",
            "DoodStream",
            "VidHide",
            "Mediafire",
            "Desuka",
            "Nozomi",
            "Desu",
            "Magi",
        )
        private val PREF_SERVER_DEFAULT = SERVER_LIST.first()

        private val DATE_FORMATTER by lazy { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH) }
        private val animePagePattern by lazy {
            Regex(
                """var\s+animes\s*=\s*(\{(?:[^"']|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')*?\})\s*;""",
                RegexOption.DOT_MATCHES_ALL,
            )
        }

        private val serverMatching = listOf(
            "voe" to listOf("voe", "tubelessceliolymph", "simpulumlamerop", "urochsunloath", "nathanfromsubject", "yip.", "metagnathtuggers", "donaldlineelse"),
            "okru" to listOf("ok.ru", "okru"),
            "filemoon" to listOf("filemoon", "moonplayer", "moviesm4u", "files.im"),
            "streamtape" to listOf("streamtape", "stp", "stape", "shavetape"),
            "mixdrop" to listOf("mixdrop", "mxdrop", "mdbekjwqa"),
            "streamwish" to listOf("sfastwish", "wishembed", "streamwish", "strwish", "wish", "Kswplayer", "Swhoi", "Multimovies", "Uqloads", "neko-stream", "swdyu", "iplayerhls", "streamgg"),
            "doostream" to listOf("d-s.io", "dsvplay"),
            "desuka" to listOf("stream/jkmedia"),
            "nozomi" to listOf("jkplayer/um2?", "um2.php", "nozomi"),
            "desu" to listOf("jkplayer/um?", "um.php"),
            "magi" to listOf("jkplayer/umv?"),
            "mega" to listOf("mega.nz"),
        )
    }
}
