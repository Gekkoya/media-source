package org.symera.mediasource.es.doramasflix

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.symera.mediasource.core.mediaSourceJson
import org.symera.mediasource.lib.fkplayer.FkPlayerDecoder
import org.symera.mediasource.multisrc.pelisplus.PelisPlus
import org.symera.source.CatalogCapability
import org.symera.source.CatalogFeed
import org.symera.source.SourceCapability
import org.symera.source.SourceEnvironment
import org.symera.source.SymeraExtensionFactory
import org.symera.source.model.ContentPage
import org.symera.source.model.ContentRating
import org.symera.source.model.ContentRelease
import org.symera.source.model.ContentStructure
import org.symera.source.model.ContentType
import org.symera.source.model.FilterList
import org.symera.source.model.PageRequest
import org.symera.source.model.PlayableItemType
import org.symera.source.model.SContent
import org.symera.source.model.SHoster
import org.symera.source.model.SPlayableItem
import org.symera.source.model.SSeason
import org.symera.source.model.SStream
import org.symera.source.network.awaitSuccess
import org.symera.source.online.GET
import org.symera.source.online.asJsoup
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import android.util.Base64 as AndroidBase64

internal fun moviePaginationVariables(request: PageRequest): JsonObject = buildJsonObject {
    put("page", JsonPrimitive(request.page))
    put("perPage", JsonPrimitive(request.pageSize ?: 20))
    put("sort", JsonPrimitive("CREATEDAT_DESC"))
}

internal fun seriesPaginationVariables(request: PageRequest): JsonObject = buildJsonObject {
    put("page", JsonPrimitive(request.page))
    put("perPage", JsonPrimitive(request.pageSize ?: 20))
    put("sort", JsonPrimitive("CREATEDAT_DESC"))
}

private val catalogJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

internal fun parseMoviePage(root: JsonObject): ContentPage {
    val pagination = root["data"]?.jsonObject?.get("paginationMovie")?.jsonObject
        ?: return ContentPage.Empty
    val items = pagination["items"]?.jsonArray?.map { element ->
        catalogJson.decodeFromString(MovieDto.serializer(), element.toString()).toSContent()
    } ?: emptyList()
    val hasNext = pagination["pageInfo"]?.jsonObject?.get("hasNextPage")
        ?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
    return ContentPage(items, hasNext)
}

internal fun parseSeriesPage(root: JsonObject): ContentPage {
    val pagination = root["data"]?.jsonObject?.get("paginationDorama")?.jsonObject
        ?: return ContentPage.Empty
    val items = pagination["items"]?.jsonArray?.map { element ->
        catalogJson.decodeFromString(DoramaDto.serializer(), element.toString()).toSContent()
    } ?: emptyList()
    val hasNext = pagination["pageInfo"]?.jsonObject?.get("hasNextPage")
        ?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
    return ContentPage(items, hasNext)
}

class Doramasflix(environment: SourceEnvironment) : PelisPlus(environment) {
    override val name = "Doramasflix"
    override val baseUrl = "https://doramasflix.io"
    override val lang = "es"
    override val contentTypes = setOf(ContentType.SERIES, ContentType.MOVIE)
    override val catalogCapabilities = setOf(CatalogCapability.MOVIES, CatalogCapability.SERIES, CatalogCapability.SEARCH)
    override val sourceCapabilities = setOf(SourceCapability.PLAYABLE_ITEMS, SourceCapability.SEASONS, SourceCapability.HOSTERS)

    private val graphqlUrl = "https://sv1.fluxcedene.net/api/gql"

    private val gqlHeaders by lazy {
        headers.newBuilder()
            .add("origin", "https://doramasflix.in")
            .add("referer", "https://doramasflix.in/")
            .add("platform", "doramasflix")
            .add("authorization", "Bear")
            .add("x-access-jwt-token", "")
            .add("x-access-platform", "RxARncfg1S_MdpSrCvreoLu_SikCGMzE1NzQzODc3NjE2MQ==")
            .build()
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val strictJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private fun gqlRequest(query: String, variables: JsonObject): Request {
        val body = buildJsonObject {
            put("query", JsonPrimitive(query))
            put("variables", variables)
        }.toString().toRequestBody(jsonMediaType)
        return Request.Builder()
            .url(graphqlUrl)
            .post(body)
            .headers(gqlHeaders)
            .build()
    }

    private fun parseGqlRoot(response: Response): JsonObject {
        val body = response.body.string().orEmpty()
        if (body.isBlank() || body.startsWith("<!")) return JsonObject(emptyMap())
        val root = mediaSourceJson.parseToJsonElement(body).jsonObject
        return root
    }

    private fun parseGqlData(response: Response): JsonObject = parseGqlRoot(response)["data"]?.jsonObject ?: JsonObject(emptyMap())

    private val dollar = "$"

    // region GraphQL Queries

    private val paginationDoramaQuery = """
        query PaginationDorama(${dollar}page: Int, ${dollar}perPage: Int, ${dollar}sort: SortFindManyDoramaInput) {
            paginationDorama(page: ${dollar}page, perPage: ${dollar}perPage, sort: ${dollar}sort) {
                pageInfo {
                    hasNextPage
                    itemCount
                    pageCount
                }
                items {
                    _id
                    name
                    name_es
                    slug
                    overview
                    original_name
                    poster_path
                    backdrop_path
                    first_air_date
                    vote_average
                    number_of_seasons
                    number_of_episodes
                    type
                    status
                    languages
                    genres { name slug }
                    seasons { slug season_number number_of_episodes _id ref }
                }
            }
        }
    """.trimIndent()

    private val paginationMovieQuery = """
        query PaginationMovie(${dollar}page: Int, ${dollar}perPage: Int, ${dollar}sort: SortFindManyMovieInput) {
            paginationMovie(page: ${dollar}page, perPage: ${dollar}perPage, sort: ${dollar}sort) {
                pageInfo {
                    hasNextPage
                    itemCount
                    pageCount
                }
                items {
                    _id
                    name
                    name_es
                    title
                    slug
                    overview
                    poster_path
                    backdrop_path
                    release_date
                    vote_average
                    runtime
                    type
                    status
                    languages
                    genres { name slug }
                }
            }
        }
    """.trimIndent()

    private val searchDoramaQuery = """
        query SearchDorama(${dollar}input: String!) {
            searchDorama(input: ${dollar}input, limit: 20) {
                _id
                name
                name_es
                slug
                poster_path
                backdrop_path
                first_air_date
                vote_average
                type
                status
                genres { name slug }
            }
        }
    """.trimIndent()

    private val searchMovieQuery = """
        query SearchMovie(${dollar}input: String!) {
            searchMovie(input: ${dollar}input, limit: 20) {
                _id
                name
                name_es
                title
                slug
                poster_path
                backdrop_path
                release_date
                vote_average
                type
                status
                genres { name slug }
            }
        }
    """.trimIndent()

    private val listSeasonsQuery = """
        query ListSeasons(${dollar}serieId: MongoID!) {
            listSeasons(sort: NUMBER_ASC, filter: { serie_id: ${dollar}serieId }) {
                slug
                season_number
                poster_path
            }
        }
    """.trimIndent()

    private val listEpisodesQuery = """
        query ListEpisodes(${dollar}seasonNumber: Float!, ${dollar}serieId: MongoID!) {
            listEpisodes(
                sort: NUMBER_ASC,
                filter: {
                    type_serie: "dorama",
                    serie_id: ${dollar}serieId,
                    season_number: ${dollar}seasonNumber
                }
            ) {
                _id
                name
                episode_number
                season_number
                still_path
            }
        }
    """.trimIndent()

    private val findDoramaBySlugQuery = """
        query FindDoramaBySlug(${dollar}slug: String!) {
            paginationDorama(page: 1, perPage: 1, filter: { slug: ${dollar}slug }) {
                items {
                    _id
                    name
                    name_es
                    slug
                    overview
                    original_name
                    poster_path
                    backdrop_path
                    first_air_date
                    vote_average
                    number_of_seasons
                    number_of_episodes
                    type
                    status
                    languages
                    genres { name slug }
                    seasons { slug season_number number_of_episodes _id ref }
                }
            }
        }
    """.trimIndent()

    private val findMovieBySlugQuery = """
        query FindMovieBySlug(${dollar}slug: String!) {
            paginationMovie(page: 1, perPage: 1, filter: { slug: ${dollar}slug }) {
                items {
                    _id
                    name
                    name_es
                    title
                    slug
                    overview
                    poster_path
                    backdrop_path
                    release_date
                    vote_average
                    runtime
                    type
                    status
                    languages
                    genres { name slug }
                }
            }
        }
    """.trimIndent()

    private val getEpisodeLinksQuery = """
        query GetEpisodeLinks(${dollar}id: MongoID!) {
            getEpisodeLinks(id: ${dollar}id) {
                _id
                links_online
            }
        }
    """.trimIndent()

    private val getMovieLinksQuery = """
        query GetMovieLinks(${dollar}id: MongoID!) {
            getMovieLinks(id: ${dollar}id) {
                _id
                links_online
            }
        }
    """.trimIndent()

    // endregion

    // region Series

    override fun seriesRequest(request: PageRequest, filters: FilterList): Request = gqlRequest(
        paginationDoramaQuery,
        seriesPaginationVariables(request),
    )

    override fun seriesParse(response: Response): ContentPage = parseSeriesPage(parseGqlRoot(response))

    // endregion

    // region Movies

    override fun moviesRequest(request: PageRequest, filters: FilterList): Request = gqlRequest(
        paginationMovieQuery,
        moviePaginationVariables(request),
    )

    override fun moviesParse(response: Response): ContentPage = parseMoviePage(parseGqlRoot(response))

    // endregion

    // region Search

    override fun searchRequest(request: PageRequest, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            val typeFilter = filters.filterIsInstance<ContentTypeFilter>().firstOrNull()
            val queryText = if (typeFilter?.toValue() == "pelicula") searchMovieQuery else searchDoramaQuery
            return gqlRequest(
                queryText,
                buildJsonObject { put("input", JsonPrimitive(query)) },
            )
        }
        return seriesRequest(request, filters)
    }

    override fun searchParse(response: Response): ContentPage {
        val data = parseGqlData(response)

        val doramaResults = data["searchDorama"]?.jsonArray
        if (doramaResults != null) {
            val items = doramaResults.map { element ->
                strictJson.decodeFromString(DoramaDto.serializer(), element.toString()).toSContent()
            }
            return ContentPage(items, false)
        }

        val movieResults = data["searchMovie"]?.jsonArray
        if (movieResults != null) {
            val items = movieResults.map { element ->
                strictJson.decodeFromString(MovieDto.serializer(), element.toString()).toSContent()
            }
            return ContentPage(items, false)
        }

        return ContentPage.Empty
    }

    // endregion

    // region Details

    override suspend fun getDetails(content: SContent): SContent {
        val slug = content.url.substringBefore("?").substringAfterLast("/")
        val details =
            if (content.contentType == ContentType.MOVIE) {
                findMovieBySlug(slug)?.toSContent()
            } else {
                findDoramaBySlug(slug)?.toSContent()
            }
        return (details ?: content).copy(url = content.url, initialized = true)
    }

    override fun contentDetailsParse(response: Response): SContent {
        val doc = response.asJsoup()
        val nextDataEl = doc.selectFirst("script#__NEXT_DATA__") ?: return htmlDetails(response, doc)
        val nextData = strictJson.parseToJsonElement(nextDataEl.data()).jsonObject
        val apolloState = nextData["props"]?.jsonObject?.get("pageProps")?.jsonObject?.get("apolloState")?.jsonObject
            ?: return htmlDetails(response, doc)

        val doramaEntry = apolloState.entries.firstOrNull {
            it.key.startsWith("Dorama:")
        }?.value?.jsonObject

        val movieEntry = apolloState.entries.firstOrNull {
            it.key.startsWith("Movie:")
        }?.value?.jsonObject

        val entry = doramaEntry ?: movieEntry
        val slug = response.request.url.encodedPath.substringAfterLast("/")
        val mongoId = entry?.get("_id")?.jsonPrimitive?.contentOrNull
        val urlWithId = if (mongoId != null) "${response.request.url.encodedPath}?_id=$mongoId" else response.request.url.encodedPath

        return SContent(
            url = urlWithId,
            title = entry?.get("name")?.jsonPrimitive?.contentOrNull
                ?: entry?.get("title")?.jsonPrimitive?.contentOrNull
                ?: "Unknown",
            description = entry?.get("overview")?.jsonPrimitive?.contentOrNull,
            posterUrl = entry?.get("poster_path")?.jsonPrimitive?.contentOrNull?.let { "https://image.tmdb.org/t/p/w500$it" },
            backdropUrl = entry?.get("backdrop_path")?.jsonPrimitive?.contentOrNull?.let { "https://image.tmdb.org/t/p/w1280$it" },
            genres = entry?.get("genres")?.jsonArray?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }.orEmpty(),
            contentType = if (doramaEntry != null) ContentType.SERIES else ContentType.MOVIE,
            release = ContentRelease(
                year = (
                    entry?.get("first_air_date")?.jsonPrimitive?.contentOrNull
                        ?: entry?.get("release_date")?.jsonPrimitive?.contentOrNull
                    )
                    ?.take(4)?.toIntOrNull(),
            ),
        )
    }

    private fun htmlDetails(response: Response, doc: org.jsoup.nodes.Document): SContent {
        val url = relativeUrl(response.request.url.toString())
        val type = if (url.contains("/peliculas/")) ContentType.MOVIE else ContentType.SERIES
        return SContent(
            url = url,
            title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.ifBlank { null } ?: "Unknown",
            posterUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null },
            contentType = type,
            structure = if (type == ContentType.MOVIE) ContentStructure.SINGLE_ITEM else ContentStructure.SEASONS,
        )
    }

    // endregion

    // region Seasons

    override suspend fun getSeasons(content: SContent): List<SSeason> {
        if (content.contentType == ContentType.MOVIE) return emptyList()
        val contentId = extractIdFromUrl(content.url)
            ?: findContentIdBySlug(content.url.substringAfterLast("/").substringBefore("?"))
            ?: return emptyList()
        return super.getSeasons(content).map { season ->
            season.copy(playableItems = loadEpisodes(contentId, season.number.toDouble()))
        }
    }

    override fun seasonsRequest(content: SContent): Request {
        val contentId = extractIdFromUrl(content.url).orEmpty()
        return gqlRequest(
            listSeasonsQuery,
            buildJsonObject {
                put("serieId", JsonPrimitive(contentId))
            },
        )
    }

    override fun seasonsParse(response: Response): List<SSeason> {
        val seasons = parseGqlData(response)["listSeasons"]?.jsonArray ?: return emptyList()
        return seasons.mapNotNull { element ->
            val seasonObj = element.jsonObject
            val seasonNum = seasonObj["season_number"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
            val number = seasonNum?.toInt() ?: return@mapNotNull null
            val url = seasonObj["slug"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            SSeason(
                url = url,
                number = number,
                title = "Temporada $number",
                posterUrl = seasonObj["poster_path"]?.jsonPrimitive?.contentOrNull?.let { "https://image.tmdb.org/t/p/w500$it" },
            )
        }
    }

    // endregion

    // region Playable Items (Episodes)

    override suspend fun getPlayableItems(content: SContent): List<SPlayableItem> {
        val contentId = extractIdFromUrl(content.url)
            ?: findContentIdBySlug(content.url.substringAfterLast("/").substringBefore("?"))
            ?: return emptyList()
        if (content.contentType == ContentType.MOVIE) {
            return listOf(
                SPlayableItem(url = "movie/$contentId", title = "Película", type = PlayableItemType.MOVIE),
            )
        }

        val seasons = client.awaitSuccess(
            gqlRequest(
                listSeasonsQuery,
                buildJsonObject { put("serieId", JsonPrimitive(contentId)) },
            ),
        ).use { response ->
            parseGqlData(response)["listSeasons"]?.jsonArray.orEmpty()
        }
        return seasons.flatMap { season ->
            val seasonNumber = season.jsonObject["season_number"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                ?: return@flatMap emptyList()
            loadEpisodes(contentId, seasonNumber)
        }
    }

    private suspend fun loadEpisodes(contentId: String, seasonNumber: Double): List<SPlayableItem> = client.awaitSuccess(
        gqlRequest(
            listEpisodesQuery,
            buildJsonObject {
                put("serieId", JsonPrimitive(contentId))
                put("seasonNumber", JsonPrimitive(seasonNumber))
            },
        ),
    ).use(::parsePlayableItems)

    private fun extractIdFromUrl(url: String): String? {
        val queryStart = url.indexOf("?_id=")
        if (queryStart == -1) return null
        val id = url.substring(queryStart + 5).substringBefore("&").substringBefore("#")
        return id.ifBlank { null }
    }

    private suspend fun findContentIdBySlug(slug: String): String? {
        val doramaId = findDoramaBySlug(slug)?.id
        if (doramaId != null) return doramaId
        return findMovieBySlug(slug)?.id
    }

    private suspend fun findDoramaBySlug(slug: String): DoramaDto? = findBySlug(slug, findDoramaBySlugQuery, "paginationDorama", DoramaDto.serializer())

    private suspend fun findMovieBySlug(slug: String): MovieDto? = findBySlug(slug, findMovieBySlugQuery, "paginationMovie", MovieDto.serializer())

    private suspend fun <T> findBySlug(
        slug: String,
        query: String,
        resultKey: String,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): T? {
        val response = client.awaitSuccess(
            gqlRequest(
                query,
                buildJsonObject { put("slug", JsonPrimitive(slug)) },
            ),
        )
        return response.use { resp ->
            val data = parseGqlData(resp)
            val item = data[resultKey]?.jsonObject?.get("items")?.jsonArray?.firstOrNull() ?: return@use null
            strictJson.decodeFromString(serializer, item.toString())
        }
    }

    private fun parsePlayableItems(response: Response): List<SPlayableItem> {
        val data = parseGqlData(response)
        return data["listEpisodes"]?.jsonArray?.mapNotNull { element ->
            val ep = strictJson.decodeFromString(EpisodeDto.serializer(), element.toString())
            val episodeNumber = ep.episodeNumber ?: return@mapNotNull null
            SPlayableItem(
                url = "episode/${ep.id}",
                title = "T${ep.seasonNumber?.toInt() ?: "?"} - E${episodeNumber.toInt()}: ${ep.name}",
                type = PlayableItemType.EPISODE,
                seasonNumber = ep.seasonNumber?.toInt(),
                episodeNumber = org.symera.source.model.EpisodeNumber(episodeNumber.toString()),
                thumbnailUrl = ep.stillPath?.let { "https://image.tmdb.org/t/p/w300$it" },
            )
        } ?: emptyList()
    }

    // endregion

    // region Hosters & Streams

    override suspend fun getHosters(item: SPlayableItem): List<SHoster> {
        val itemType = item.url.substringBefore("/")
        val itemId = item.url.substringAfterLast("/")
        val query = if (itemType == "movie") getMovieLinksQuery else getEpisodeLinksQuery
        val response = client.awaitSuccess(
            gqlRequest(
                query,
                buildJsonObject { put("id", JsonPrimitive(itemId)) },
            ),
        )
        return response.use(::parseHosters)
    }

    override fun playableItemsParse(response: Response): List<SPlayableItem> = parsePlayableItems(response)

    override fun hostersParse(response: Response): List<SHoster> = parseHosters(response)

    private fun parseHosters(response: Response): List<SHoster> {
        val data = parseGqlData(response)
        val source = data["getEpisodeLinks"] ?: data["getMovieLinks"] ?: return emptyList()
        val sourceObject = source.jsonObject
        val links = sourceObject["links_online"]?.asLinkElements().orEmpty()
        val problems = (sourceObject["listProblems"] ?: data["listProblems"])
            ?.asLinkElements()
            .orEmpty()
        return parseLinksOnline(links + problems)
    }

    private fun parseLinksOnline(linksJson: List<kotlinx.serialization.json.JsonElement>): List<SHoster> {
        return linksJson.mapNotNull { element ->
            val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val linkObject = obj["server"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: obj
            val link = linkObject["link"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val embed = linkObject["embed"]?.jsonPrimitive?.contentOrNull
            val playbackUrl = DoramasflixHosterParser.effectiveUrl(link, embed)
            val serverName = linkObject.stringValue("server")
                ?: obj.stringValue("server")
                ?: ""
            val parsed = DoramasflixHosterParser.parseOne(playbackUrl, serverName) ?: return@mapNotNull null
            val lang = linkLanguage(
                linkObject.stringValue("lang")
                    ?: obj.stringValue("lang"),
            )

            SHoster(
                id = parsed.url,
                name = "$lang ${parsed.hostName}".trim(),
                requestUrl = parsed.url,
                resolverData = parsed.url,
            )
        }.distinctBy { it.id }
    }

    override suspend fun getStreams(hoster: SHoster): List<SStream> {
        val url = decodeFkPlayerUrl(hoster.requestUrl ?: return emptyList())
        return serverVideoResolver(url, hoster.name, url).sortStreams()
    }

    private suspend fun decodeFkPlayerUrl(url: String): String {
        if (!url.contains("fkplayer.xyz")) return url
        FkPlayerDecoder.decode(url)?.let { return it }
        // Legacy fallback: /api/decoding with a page token.
        return try {
            val tokenJson = client.awaitSuccess(GET(url, headers)).use { response ->
                response.asJsoup()
                    .selectFirst("script:containsData({\"props\":{\"pageProps\":{)")
                    ?.data()
                    ?: return url
            }
            val tokenRoot = strictJson.parseToJsonElement(tokenJson).jsonObject
            val token = tokenRoot["props"]?.jsonObject
                ?.get("pageProps")?.jsonObject
                ?.get("token")?.jsonPrimitive?.contentOrNull
                ?: tokenRoot["query"]?.jsonObject?.get("token")?.jsonPrimitive?.contentOrNull
                ?: return url
            val payload = buildJsonObject { put("token", JsonPrimitive(token)) }
                .toString()
                .toRequestBody(jsonMediaType)
            val decoderHeaders = headers.newBuilder()
                .add("origin", "https://${url.toHttpUrl().host}")
                .build()
            val encodedUrl = client.awaitSuccess(
                Request.Builder()
                    .url("https://fkplayer.xyz/api/decoding")
                    .headers(decoderHeaders)
                    .post(payload)
                    .build(),
            ).use { response ->
                strictJson.parseToJsonElement(response.body.string())
                    .jsonObject["link"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?: return url
            }
            String(AndroidBase64.decode(encodedUrl, AndroidBase64.DEFAULT))
        } catch (_: Exception) {
            url
        }
    }

    override fun List<SStream>.sortStreams(): List<SStream> {
        val prefs = environment.preferencesFor(sourcePreferenceNamespace)
        val quality = prefs.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
        return sortedWith(
            compareBy(
                { it.title.orEmpty().contains(quality) },
                { Regex("""(\d+)p""").find(it.title.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    // endregion

    // region Filters & Preferences

    override fun getFilterList(feed: CatalogFeed) = if (feed == CatalogFeed.SEARCH) {
        FilterList(ContentTypeFilter())
    } else {
        FilterList()
    }

    override fun getSourcePreferences() = listOf(
        org.symera.source.model.SourcePreference.Select(
            key = PREF_QUALITY_KEY,
            title = "Preferred quality",
            values = qualityList.map { org.symera.source.model.SourcePreference.Option(it) },
            summary = "%s",
            defaultValue = PREF_QUALITY_DEFAULT,
        ),
    )

    // endregion

    // region Helpers

    private fun linkLanguage(langId: String?): String = when (langId) {
        "36" -> "[ENG]"
        "37" -> "[CAST]"
        "38" -> "[LAT]"
        "192" -> "[SUB]"
        "1327" -> "[POR]"
        "13109" -> "[COR]"
        "13110" -> "[JAP]"
        "13111" -> "[MAN]"
        "13112" -> "[TAI]"
        "13113" -> "[FIL]"
        "13114" -> "[IND]"
        "343422" -> "[VIET]"
        else -> ""
    }

    // endregion

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val qualityList = listOf("1080", "720", "480", "360")
    }
}

private fun DoramaDto.toSContent() = SContent(
    url = "/doramas/$slug?_id=$id",
    title = nameEs ?: name,
    description = overview,
    posterUrl = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
    backdropUrl = backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" },
    genres = genres?.mapNotNull { it.name }.orEmpty(),
    contentType = ContentType.SERIES,
    structure = ContentStructure.SEASONS,
    release = ContentRelease(year = firstAirDate?.take(4)?.toIntOrNull()),
    rating = voteAverage?.let { ContentRating(it, maximum = 10.0) },
)

private fun MovieDto.toSContent() = SContent(
    url = "/peliculas/$slug?_id=$id",
    title = nameEs ?: title ?: name,
    description = overview,
    posterUrl = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
    backdropUrl = backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" },
    genres = genres?.mapNotNull { it.name }.orEmpty(),
    contentType = ContentType.MOVIE,
    structure = ContentStructure.SINGLE_ITEM,
    release = ContentRelease(year = releaseDate?.take(4)?.toIntOrNull()),
    rating = voteAverage?.let { ContentRating(it, maximum = 10.0) },
)

internal data class DoramasflixParsedHoster(
    val url: String,
    val hostName: String,
)

internal object DoramasflixHosterParser {
    fun parse(elements: List<JsonElement>): List<DoramasflixParsedHoster> = elements.mapNotNull { element ->
        val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
        val linkObject = obj["server"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: obj
        val link = linkObject["link"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val embed = linkObject["embed"]?.jsonPrimitive?.contentOrNull
        val serverName = linkObject.stringValue("server")
            ?: obj.stringValue("server")
            ?: ""
        parseOne(effectiveUrl(link, embed), serverName)
    }.distinctBy { it.url }

    fun parsePayload(
        linksOnline: List<JsonElement>,
        listProblems: List<JsonElement>,
    ): List<DoramasflixParsedHoster> = parse(linksOnline + listProblems)

    fun parseOne(url: String, serverName: String): DoramasflixParsedHoster? {
        val effectiveUrl = url.trim()
        if (effectiveUrl.isBlank()) return null
        val source = "$effectiveUrl $serverName".lowercase()
        if ("mediafire" in source || RETIRED_HOSTS.any { it in effectiveUrl.lowercase() }) return null
        return DoramasflixParsedHoster(effectiveUrl, hostName(effectiveUrl, serverName))
    }

    fun effectiveUrl(link: String, embed: String?): String = when {
        link.contains("fkplayer.xyz", ignoreCase = true) -> link
        !embed.isNullOrBlank() -> embed
        else -> unwrapEmbedShortener(link)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun unwrapEmbedShortener(url: String): String {
        if (!url.contains("embedshortener.co", ignoreCase = true)) return url
        return runCatching {
            val payload = url.substringAfterLast("/e/").substringBefore('?').split('.').getOrNull(1)
                ?: return@runCatching url
            val payloadJson = String(Base64.UrlSafe.decode(payload.padBase64()))
            val encodedLink = Json.parseToJsonElement(payloadJson).jsonObject["link"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?: return@runCatching url
            String(Base64.decode(encodedLink.padBase64()))
        }.getOrDefault(url)
    }

    private fun String.padBase64(): String = this + "=".repeat((4 - length % 4) % 4)

    fun hostName(url: String, serverName: String = ""): String {
        val lowerUrl = url.lowercase()
        return when {
            "dood" in lowerUrl || "d-s.io" in lowerUrl || "dsvplay" in lowerUrl || "do7go" in lowerUrl -> "DoodStream"
            "voe" in lowerUrl || "tubelessceliolymph" in lowerUrl || "simpulumlamerop" in lowerUrl -> "Voe"
            "streamtape" in lowerUrl || "stp" in lowerUrl || "stape" in lowerUrl -> "StreamTape"
            "streamwish" in lowerUrl || "sfastwish" in lowerUrl || "wishembed" in lowerUrl || "strwish" in lowerUrl -> "StreamWish"
            "filemoon" in lowerUrl || "moonplayer" in lowerUrl || "files.im" in lowerUrl -> "FileMoon"
            "okru" in lowerUrl || "ok.ru" in lowerUrl -> "Okru"
            "mixdrop" in lowerUrl || "mxdrop" in lowerUrl -> "MixDrop"
            "vidhide" in lowerUrl || "vidhidepre" in lowerUrl -> "VidHide"
            "uqload" in lowerUrl -> "UqLoad"
            "vudeo" in lowerUrl -> "Vudeo"
            "bysefujedu" in lowerUrl -> "Byse"
            "primeload" in lowerUrl -> "PrimeLoad"
            "streamsb" in lowerUrl || "playersb" in lowerUrl -> "StreamSB"
            serverName.isNotBlank() && !serverName.all(Char::isDigit) -> serverName
            else -> url.substringAfter("://").substringBefore("/").substringBefore("?")
        }
    }

    private val RETIRED_HOSTS = setOf("vudeo.co", "repro3.estrenosdoramas.us")
}

private fun JsonElement.asLinkElements(): List<JsonElement> = when {
    this is kotlinx.serialization.json.JsonArray -> this
    this is JsonObject -> this["json"]?.asLinkElements() ?: listOf(this)
    else -> emptyList()
}

private fun JsonObject.stringValue(key: String): String? = runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

object DoramasflixFactory : SymeraExtensionFactory {
    override fun createVodSources(environment: SourceEnvironment) = listOf(Doramasflix(environment))
}
