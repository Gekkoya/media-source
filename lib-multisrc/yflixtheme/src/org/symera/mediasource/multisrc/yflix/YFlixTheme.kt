package org.symera.mediasource.multisrc.yflix

import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.symera.mediasource.core.parseAs
import org.symera.mediasource.lib.rapidshare.RapidShareExtractor
import org.symera.source.ConfigurableSymeraSource
import org.symera.source.model.ContentPage
import org.symera.source.model.ContentStatus
import org.symera.source.model.ContentType
import org.symera.source.model.FilterList
import org.symera.source.model.SContent
import org.symera.source.model.SHoster
import org.symera.source.model.SPlayableItem
import org.symera.source.model.SStream
import org.symera.source.model.SourcePreference
import org.symera.source.online.GET
import org.symera.source.online.SymeraHttpSource
import org.symera.source.online.asJsoup
import org.symera.source.online.awaitSuccess
import org.symera.source.preferenceValues
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Locale

open class YFlixTheme(
    override val name: String,
    protected val domainList: List<String>,
    protected val defaultDomain: String = "https://${domainList.first()}",
    override val lang: String = "en",
) : SymeraHttpSource(),
    ConfigurableSymeraSource {

    override val contentTypes = setOf(ContentType.MOVIE, ContentType.SERIES)

    override val baseUrl: String
        get() = preferenceValues().getString(PREF_DOMAIN_KEY, defaultDomain).let { configured ->
            val host = configured.toHttpUrlOrNull()?.host ?: configured
            if (host in domainList) configured else defaultDomain
        }

    protected open val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    protected open val encdecHeaders: Headers by lazy {
        headers.newBuilder()
            .set("User-Agent", DEFAULT_USER_AGENT)
            .build()
    }

    protected open fun headersReferrerBuilder(url: String = baseUrl): Headers.Builder = headers.newBuilder()
        .set("Referer", "$url/")

    protected open val docHeaders: Headers
        get() = headersReferrerBuilder().build()

    protected open fun rapidShareExtractor(referer: String = baseUrl): RapidShareExtractor = RapidShareExtractor(client, headersReferrerBuilder(referer).build())

    override fun moviesRequest(page: Int): Request = browserRequest(page, type = "movie")

    override fun moviesParse(response: Response): ContentPage = parseContentPage(response)

    override fun seriesRequest(page: Int): Request = browserRequest(page, type = "tv")

    override fun seriesParse(response: Response): ContentPage = parseContentPage(response)

    protected open fun browserRequest(page: Int, type: String): Request {
        val url = "$baseUrl/browser".toHttpUrl().newBuilder()
            .addQueryParameter("type[]", type)
            .addQueryParameter("sort", "trending")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url.toString(), docHeaders)
    }

    override fun searchRequest(page: Int, query: String, filters: FilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val url = "$baseUrl/browser".toHttpUrl().newBuilder()
            .addQueryParameter("keyword", query)
            .addQueryParameter("page", page.toString())
            .also { builder ->
                YFlixThemeFilters.getFilters(filterList).forEach { it.addQueryParameters(builder) }
            }.build()
        return GET(url.toString(), docHeaders)
    }

    override fun searchParse(response: Response): ContentPage = parseContentPage(response)

    protected open val moviesSelector = "div.film-section div.item"

    protected open fun parseContentPage(response: Response): ContentPage {
        val document = response.asJsoup()
        val contents = document.select(moviesSelector).mapNotNull { item ->
            val poster = item.selectFirst("a.poster") ?: return@mapNotNull null
            val title = item.selectFirst("a.title")?.text() ?: return@mapNotNull null
            SContent.create().apply {
                setUrlWithoutDomain(poster.attr("href"))
                this.title = title
                posterUrl = item.selectFirst("img")?.attr("abs:data-src")?.takeIf { it.isNotBlank() }
                    ?: item.selectFirst("img")?.attr("abs:src")
                contentType = parseListingType(item)
            }
        }
        val hasNextPage = document.selectFirst("li.page-item a[rel=next]") != null
        return ContentPage(contents, hasNextPage)
    }

    protected open fun parseListingType(item: Element): ContentType? {
        val metadata = item.text()
        return when {
            metadata.contains("TV", true) -> ContentType.SERIES
            metadata.contains("Movie", true) -> ContentType.MOVIE
            else -> null
        }
    }

    protected open fun Document.isMovie(): Boolean = selectFirst(".metadata > span:contains(Movie)") != null

    override fun contentDetailsParse(response: Response): SContent {
        val document = response.asJsoup()
        val isMovie = document.isMovie()
        return SContent.create().apply {
            url = response.request.url.encodedPath
            title = document.selectFirst("h1.title")?.text().orEmpty()
            posterUrl = document.selectFirst("div.poster img")?.attr("abs:src")
            backdropUrl = document.getBackdropUrl()
            contentType = if (isMovie) ContentType.MOVIE else ContentType.SERIES
            status = if (isMovie) ContentStatus.COMPLETED else ContentStatus.ONGOING
            genres = document.select("ul.mics li:has(a[href*=/genre/]) a").eachText()
            rating = document.getScore()?.toDoubleOrNull()
            description = buildString {
                val scorePosition = preferenceValues().getString(PREF_SCORE_POSITION_KEY, PREF_SCORE_POSITION_DEFAULT)
                val fancyScore = when (scorePosition) {
                    SCORE_POS_TOP, SCORE_POS_BOTTOM -> document.getFancyScore()
                    else -> ""
                }

                if (scorePosition == SCORE_POS_TOP && fancyScore.isNotEmpty()) {
                    append(fancyScore)
                    append("\n\n")
                }

                document.selectFirst(".description")?.text()?.also { append("$it\n\n") }
                append("**Type:** ${if (isMovie) "Movie" else "TV Show"}\n")

                fun getInfo(label: String): String? = document.selectFirst("ul.mics li:contains($label:)")
                    ?.text()
                    ?.substringAfter(":")
                    ?.trim()

                getInfo("Country")?.let { append("**Country:** $it\n") }
                getInfo("Released")?.let {
                    append("**Released:** $it\n")
                    year = it.toIntOrNull()
                }
                getInfo("Casts")?.let { append("**Casts:** $it\n") }
                document.selectFirst(".metadata .IMDb")?.text()?.substringAfter("IMDb")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    append("**IMDb:** $it")
                }

                if (scorePosition == SCORE_POS_BOTTOM && fancyScore.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append(fancyScore)
                }
            }
        }
    }

    protected open val backgroundUrlRegex: Regex by lazy { """background-image:\s*url\(["']?([^"')]+)["']?\)""".toRegex() }

    protected open fun Document.getBackdropUrl(): String? = selectFirst("div.detail-bg")
        ?.attr("style")
        ?.let { backgroundUrlRegex.find(it)?.groupValues?.getOrNull(1) }

    protected open fun Document.getScore(): String? = selectFirst("div.rating")?.attr("data-score")

    protected open fun Document.getFancyScore(): String {
        val score = getScore()
        if (score.isNullOrBlank()) return ""
        return try {
            val scoreBig = BigDecimal(score.trim())
            if (scoreBig.compareTo(BigDecimal.ZERO) == 0) return ""
            val stars = scoreBig.divide(BigDecimal(2)).setScale(0, RoundingMode.HALF_UP).toInt().coerceIn(0, 5)
            val scoreString = scoreBig.stripTrailingZeros().toPlainString()
            "Rating: $scoreString/10 ($stars/5)"
        } catch (_: NumberFormatException) {
            ""
        }
    }

    override fun getFilterList(): FilterList = YFlixThemeFilters.FILTER_LIST

    protected open fun Document.contentIdSelect(): String? = selectFirst("div.rating[data-id]")?.attr("data-id")

    override fun playableItemsParse(response: Response): List<SPlayableItem> {
        val animeUrl = response.request.url.encodedPath
        val document = response.asJsoup()
        val contentId = document.contentIdSelect() ?: return emptyList()
        val encryptedId = encryptBlocking(contentId)
        val ajaxUrl = "$baseUrl/ajax/episodes/list?id=$contentId&_=$encryptedId"
        val resultDoc = client.newCall(GET(ajaxUrl, ajaxHeaders(baseUrl + animeUrl)))
            .execute()
            .parseAs<ResultResponse>(json = json)
            .toDocument()

        return resultDoc.select("ul.episodes[data-season]").flatMap { seasonElement ->
            val seasonNum = seasonElement.attr("data-season")
            seasonElement.select("li a").map { element ->
                if (element.selectFirst("span.num") != null) {
                    tvPlayableItemFromElement(element, animeUrl, seasonNum)
                } else {
                    moviePlayableItemFromElement(element, animeUrl)
                }
            }
        }.reversed()
    }

    protected open fun tvPlayableItemFromElement(element: Element, animeUrl: String, seasonNum: String): SPlayableItem = SPlayableItem.create().apply {
        val epNum = element.attr("num")
        url = "$animeUrl#${element.attr("eid")}"
        seasonNumber = seasonNum.toDoubleOrNull()
        episodeNumber = epNum.toDoubleOrNull() ?: 0.0
        title = "S$seasonNum E$epNum: ${element.selectFirst("span:not(.num)")?.text()?.trim()}"
        airDate = parseDate(element.attr("title"))
    }

    protected open fun moviePlayableItemFromElement(element: Element, animeUrl: String): SPlayableItem = SPlayableItem.create().apply {
        url = "$animeUrl#${element.attr("eid")}"
        episodeNumber = 1.0
        title = element.selectFirst("span")?.text()?.trim() ?: "Movie"
    }

    protected open val serversSelector = "li.server"

    override suspend fun getHosters(item: SPlayableItem): List<SHoster> {
        val (animeUrl, episodeId) = item.url.split('#', limit = 2)
        val referer = baseUrl + animeUrl
        val encryptedId = encrypt(episodeId)
        val serversUrl = "$baseUrl/ajax/links/list?eid=$episodeId&_=$encryptedId"
        val serversDoc = client.newCall(GET(serversUrl, ajaxHeaders(referer)))
            .awaitSuccess()
            .parseAs<ResultResponse>(json = json)
            .toDocument()
        val enabledHosters = preferenceValues().getStringSet(PREF_HOSTER_KEY, SERVERS.toSet())

        return serversDoc.select(serversSelector).mapNotNull { serverElement ->
            val serverName = serverElement.selectFirst("span")?.text() ?: return@mapNotNull null
            if (serverName !in enabledHosters) return@mapNotNull null
            val serverId = serverElement.attr("data-lid")
            val encryptedServerId = encrypt(serverId)
            val viewUrl = "$baseUrl/ajax/links/view?id=$serverId&_=$encryptedServerId"
            val encryptedIframeResult = client.newCall(GET(viewUrl, ajaxHeaders(referer)))
                .awaitSuccess()
                .parseAs<ResultResponse>(json = json)
                .result
            val iframeUrl = decrypt(encryptedIframeResult)
            SHoster(
                hosterUrl = iframeUrl,
                hosterName = serverName,
                displayName = serverName,
                internalData = serverName,
                lazy = true,
            )
        }.sortHosters()
    }

    override fun hostersParse(response: Response): List<SHoster> = emptyList()

    override suspend fun getStreams(hoster: SHoster): List<SStream> {
        val preferences = preferenceValues()
        val preferredLang = preferences.getString(PREF_SUB_LANG_KEY, PREF_SUB_LANG_DEFAULT)
        val serverName = hoster.internalData.ifBlank { hoster.hosterName }
        return rapidShareExtractor(hoster.hosterUrl).streamsFromUrl(hoster.hosterUrl, serverName, preferredLang).sortStreams()
    }

    override fun streamsParse(response: Response, hoster: SHoster): List<SStream> = emptyList()

    protected open fun ajaxHeaders(referer: String): Headers = docHeaders.newBuilder()
        .set("Referer", referer)
        .add("Accept", "application/json, text/javascript, */*; q=0.01")
        .add("X-Requested-With", "XMLHttpRequest")
        .build()

    protected open fun encryptBlocking(text: String): String = client.newCall(
        GET("https://enc-dec.app/api/enc-movies-flix?text=$text", encdecHeaders),
    ).execute().parseAs<ResultResponse>(json = json).result

    protected open suspend fun encrypt(text: String): String = client.newCall(
        GET("https://enc-dec.app/api/enc-movies-flix?text=$text", encdecHeaders),
    ).awaitSuccess().parseAs<ResultResponse>(json = json).result

    protected open suspend fun decrypt(text: String): String = client.newCall(
        GET("https://enc-dec.app/api/dec-movies-flix?text=$text", encdecHeaders),
    ).awaitSuccess().parseAs<DecryptedIframeResponse>(json = json).result.url

    protected open fun parseDate(dateStr: String): Long = runCatching { DATE_FORMATTER.parse(dateStr)?.time }.getOrNull() ?: 0L

    override fun List<SStream>.sortStreams(): List<SStream> {
        val preferences = preferenceValues()
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)
        val qualities = QUALITIES.reversed()
        return sortedWith(
            compareByDescending<SStream> {
                it.title.contains(quality, true) && it.title.startsWith(server, true)
            }
                .thenByDescending { it.title.contains(quality, true) }
                .thenByDescending { it.title.startsWith(server, true) }
                .thenByDescending { stream -> qualities.indexOfFirst { stream.title.contains(it) } },
        )
    }

    override fun getSourcePreferences(): List<SourcePreference<*>> = listOf(
        SourcePreference.Select(
            key = PREF_DOMAIN_KEY,
            title = "Preferred domain",
            values = domainList.map { SourcePreference.Option(value = "https://$it", label = it) },
            summary = "%s",
            defaultValue = defaultDomain,
        ),
        SourcePreference.Select(
            key = PREF_QUALITY_KEY,
            title = "Preferred quality",
            values = QUALITIES.map { SourcePreference.Option(it) },
            summary = "%s",
            defaultValue = PREF_QUALITY_DEFAULT,
        ),
        SourcePreference.Select(
            key = PREF_SUB_LANG_KEY,
            title = "Preferred sub language",
            values = SUB_LANGS.map { SourcePreference.Option(it) },
            summary = "%s",
            defaultValue = PREF_SUB_LANG_DEFAULT,
        ),
        SourcePreference.Select(
            key = PREF_SERVER_KEY,
            title = "Preferred server",
            values = SERVERS.map { SourcePreference.Option(it) },
            summary = "%s",
            defaultValue = PREF_SERVER_DEFAULT,
        ),
        SourcePreference.Select(
            key = PREF_SCORE_POSITION_KEY,
            title = "Score display position",
            values = PREF_SCORE_POSITION_ENTRIES.zip(PREF_SCORE_POSITION_VALUES).map { (label, value) -> SourcePreference.Option(value, label) },
            summary = "%s",
            defaultValue = PREF_SCORE_POSITION_DEFAULT,
        ),
        SourcePreference.MultiSelect(
            key = PREF_HOSTER_KEY,
            title = "Enable/disable servers",
            values = SERVERS.map { SourcePreference.Option(it) },
            summary = "Select which video server to show in the episode list",
            defaultValue = SERVERS.toSet(),
        ),
    )

    companion object {
        private const val PREF_DOMAIN_KEY = "pref_domain_key"

        const val PREF_QUALITY_KEY = "pref_quality_key"
        protected val QUALITIES = listOf("1080p", "720p", "480p", "360p")
        protected val PREF_QUALITY_DEFAULT = QUALITIES.first()

        const val PREF_SUB_LANG_KEY = "pref_sub_lang_key"
        protected val SUB_LANGS = listOf(
            "English",
            "Arabic",
            "Chinese",
            "French",
            "German",
            "Indonesian",
            "Italian",
            "Japanese",
            "Korean",
            "Persian",
            "Portuguese",
            "Russian",
            "Spanish",
            "Turkish",
            "Urdu",
            "Vietnamese",
        )
        internal val PREF_SUB_LANG_DEFAULT = SUB_LANGS.first()

        const val PREF_SERVER_KEY = "pref_server_key"
        protected val SERVERS = listOf("Server 1", "Server 2")
        protected val PREF_SERVER_DEFAULT = SERVERS.first()

        const val PREF_HOSTER_KEY = "pref_hoster_key"

        protected const val PREF_SCORE_POSITION_KEY = "score_position"
        protected const val SCORE_POS_TOP = "top"
        protected const val SCORE_POS_BOTTOM = "bottom"
        protected const val SCORE_POS_NONE = "none"
        protected const val PREF_SCORE_POSITION_DEFAULT = SCORE_POS_TOP
        protected val PREF_SCORE_POSITION_ENTRIES = listOf(
            "Top of description",
            "Bottom of description",
            "Don't show",
        )
        protected val PREF_SCORE_POSITION_VALUES = listOf(
            SCORE_POS_TOP,
            SCORE_POS_BOTTOM,
            SCORE_POS_NONE,
        )

        private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"

        protected val DATE_FORMATTER: SimpleDateFormat by lazy {
            SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
        }
    }
}
