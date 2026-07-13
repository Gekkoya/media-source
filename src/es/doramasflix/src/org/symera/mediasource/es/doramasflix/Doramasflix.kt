package org.symera.mediasource.es.doramasflix

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.symera.mediasource.core.Source
import org.symera.mediasource.core.mediaSourceJson
import org.symera.mediasource.lib.dood.DoodExtractor
import org.symera.mediasource.lib.filemoon.FilemoonExtractor
import org.symera.mediasource.lib.streamtape.StreamTapeExtractor
import org.symera.mediasource.lib.streamwish.StreamWishExtractor
import org.symera.mediasource.lib.universal.UniversalExtractor
import org.symera.mediasource.lib.voe.VoeExtractor
import org.symera.source.model.ContentPage
import org.symera.source.model.ContentStatus
import org.symera.source.model.ContentType
import org.symera.source.model.FilterList
import org.symera.source.model.SContent
import org.symera.source.model.SHoster
import org.symera.source.model.SPlayableItem
import org.symera.source.model.SSeason
import org.symera.source.model.SStream
import org.symera.source.online.asJsoup
import org.symera.source.online.awaitSuccess
import org.symera.source.preferenceValues

class Doramasflix : Source() {
    override val name = "Doramasflix"
    override val baseUrl = "https://doramasflix.io"
    override val lang = "es"
    override val contentTypes = setOf(ContentType.SERIES, ContentType.MOVIE)

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

    private fun parseGqlData(response: Response): JsonObject {
        val body = response.body.string().orEmpty()
        if (body.isBlank() || body.startsWith("<!")) return JsonObject(emptyMap())
        val root = mediaSourceJson.parseToJsonElement(body).jsonObject
        return root["data"]?.jsonObject ?: JsonObject(emptyMap())
    }

    private val dollar = "$"

    // region GraphQL Queries

    private val paginationDoramaQuery = """
        query PaginationDorama(${dollar}page: Int, ${dollar}perPage: Int) {
            paginationDorama(page: ${dollar}page, perPage: ${dollar}perPage) {
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
        query PaginationMovie(${dollar}page: Int, ${dollar}perPage: Int) {
            paginationMovie(page: ${dollar}page, perPage: ${dollar}perPage) {
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
                    slug
                }
            }
        }
    """.trimIndent()

    private val findMovieBySlugQuery = """
        query FindMovieBySlug(${dollar}slug: String!) {
            paginationMovie(page: 1, perPage: 1, filter: { slug: ${dollar}slug }) {
                items {
                    _id
                    slug
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

    override fun seriesRequest(page: Int): Request = gqlRequest(
        paginationDoramaQuery,
        buildJsonObject {
            put("page", JsonPrimitive(page))
            put("perPage", JsonPrimitive(20))
        },
    )

    override fun seriesParse(response: Response): ContentPage {
        val data = parseGqlData(response)
        val pagination = data["paginationDorama"]?.jsonObject ?: return ContentPage.Empty
        val items = pagination["items"]?.jsonArray?.map { element ->
            strictJson.decodeFromString(DoramaDto.serializer(), element.toString()).toSContent()
        } ?: emptyList()
        val hasNext = pagination["pageInfo"]?.jsonObject?.get("hasNextPage")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        return ContentPage(items, hasNext)
    }

    // endregion

    // region Movies

    override fun moviesRequest(page: Int): Request = gqlRequest(
        paginationMovieQuery,
        buildJsonObject {
            put("page", JsonPrimitive(page))
            put("perPage", JsonPrimitive(20))
        },
    )

    override fun moviesParse(response: Response): ContentPage {
        val data = parseGqlData(response)
        val pagination = data["paginationMovie"]?.jsonObject ?: return ContentPage.Empty
        val items = pagination["items"]?.jsonArray?.map { element ->
            strictJson.decodeFromString(MovieDto.serializer(), element.toString()).toSContent()
        } ?: emptyList()
        val hasNext = pagination["pageInfo"]?.jsonObject?.get("hasNextPage")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        return ContentPage(items, hasNext)
    }

    // endregion

    // region Search

    override fun searchRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            return gqlRequest(
                searchDoramaQuery,
                buildJsonObject { put("input", JsonPrimitive(query)) },
            )
        }
        return seriesRequest(page)
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

    override fun contentDetailsParse(response: Response): SContent {
        val doc = response.asJsoup()
        val nextDataEl = doc.selectFirst("script#__NEXT_DATA__") ?: return SContent.create().apply {
            url = response.request.url.encodedPath
            title = ""
        }
        val nextData = strictJson.parseToJsonElement(nextDataEl.data()).jsonObject
        val apolloState = nextData["props"]?.jsonObject?.get("pageProps")?.jsonObject?.get("apolloState")?.jsonObject
            ?: return SContent.create().apply {
                url = response.request.url.encodedPath
                title = ""
            }

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

        return SContent.create().apply {
            url = urlWithId
            title = entry?.get("name")?.jsonPrimitive?.contentOrNull
                ?: entry?.get("title")?.jsonPrimitive?.contentOrNull
                ?: ""
            description = entry?.get("overview")?.jsonPrimitive?.contentOrNull
            posterUrl = entry?.get("poster_path")?.jsonPrimitive?.contentOrNull?.let { "https://image.tmdb.org/t/p/w500$it" }
            backdropUrl = entry?.get("backdrop_path")?.jsonPrimitive?.contentOrNull?.let { "https://image.tmdb.org/t/p/w1280$it" }
            genres = entry?.get("genres")?.jsonArray?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
            status = ContentStatus.UNKNOWN
            contentType = if (doramaEntry != null) ContentType.SERIES else ContentType.MOVIE
            year = (
                entry?.get("first_air_date")?.jsonPrimitive?.contentOrNull
                    ?: entry?.get("release_date")?.jsonPrimitive?.contentOrNull
                )
                ?.take(4)?.toIntOrNull()
        }
    }

    // endregion

    // region Seasons

    override suspend fun getSeasons(content: SContent): List<SSeason> {
        if (content.contentType == ContentType.MOVIE) return emptyList()
        return super.getSeasons(content)
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
        return seasons.map { element ->
            val seasonObj = element.jsonObject
            val seasonNum = seasonObj["season_number"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
            SSeason.create().apply {
                this.seasonNumber = seasonNum
                title = "Temporada ${seasonNum?.toInt() ?: "?"}"
                description = null
                posterUrl = seasonObj["poster_path"]?.jsonPrimitive?.contentOrNull
                    ?.let { "https://image.tmdb.org/t/p/w500$it" }
                url = seasonObj["slug"]?.jsonPrimitive?.contentOrNull.orEmpty()
            }
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
                SPlayableItem.create().apply {
                    setUrlWithoutDomain("movie/$contentId")
                    title = "Película"
                    episodeNumber = 1.0
                },
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
            client.awaitSuccess(
                gqlRequest(
                    listEpisodesQuery,
                    buildJsonObject {
                        put("serieId", JsonPrimitive(contentId))
                        put("seasonNumber", JsonPrimitive(seasonNumber))
                    },
                ),
            ).use(::parsePlayableItems)
        }
    }

    private fun extractIdFromUrl(url: String): String? {
        val queryStart = url.indexOf("?_id=")
        if (queryStart == -1) return null
        val id = url.substring(queryStart + 5).substringBefore("&").substringBefore("#")
        return id.ifBlank { null }
    }

    private suspend fun findContentIdBySlug(slug: String): String? {
        val doramaId = findIdBySlug(slug, findDoramaBySlugQuery)
        if (doramaId != null) return doramaId
        return findIdBySlug(slug, findMovieBySlugQuery)
    }

    private suspend fun findIdBySlug(slug: String, query: String): String? {
        val response = client.awaitSuccess(
            gqlRequest(
                query,
                buildJsonObject { put("slug", JsonPrimitive(slug)) },
            ),
        )
        return response.use { resp ->
            val data = parseGqlData(resp)
            val pagination = data["paginationDorama"]?.jsonObject ?: data["paginationMovie"]?.jsonObject
            pagination?.get("items")?.jsonArray?.firstOrNull()?.jsonObject?.get("_id")?.jsonPrimitive?.contentOrNull
        }
    }

    private fun parsePlayableItems(response: Response): List<SPlayableItem> {
        val data = parseGqlData(response)
        return data["listEpisodes"]?.jsonArray?.map { element ->
            val ep = strictJson.decodeFromString(EpisodeDto.serializer(), element.toString())
            SPlayableItem.create().apply {
                seasonNumber = ep.seasonNumber
                episodeNumber = ep.episodeNumber
                title = "T${ep.seasonNumber?.toInt() ?: "?"} - E${ep.episodeNumber?.toInt() ?: "?"}: ${ep.name}"
                thumbnailUrl = ep.stillPath?.let { "https://image.tmdb.org/t/p/w300$it" }
                setUrlWithoutDomain("episode/${ep.id}")
            }
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
        val linksArr = source.jsonObject["links_online"]?.jsonArray ?: return emptyList()
        return parseLinksOnline(linksArr)
    }

    private fun parseLinksOnline(linksJson: List<kotlinx.serialization.json.JsonElement>): List<SHoster> {
        return linksJson.mapNotNull { element ->
            val obj = try {
                element.jsonObject
            } catch (_: Exception) {
                null
            } ?: return@mapNotNull null

            val link = obj["link"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val langId = obj["lang"]?.jsonPrimitive?.contentOrNull
            val lang = getLang(langId)
            val serverName = obj["server"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val embed = obj["embed"]?.jsonPrimitive?.contentOrNull
            val hosterName = extractHosterName(
                embed ?: link,
                if (serverName.all(Char::isDigit)) "" else serverName,
            )

            SHoster(
                hosterUrl = link,
                hosterName = hosterName,
                displayName = "$lang $hosterName".trim(),
                internalData = link,
                lazy = true,
            )
        }
    }

    override fun streamsParse(response: Response, hoster: SHoster): List<SStream> = emptyList()

    override suspend fun getStreams(hoster: SHoster): List<SStream> {
        val url = decodeFkPlayerUrl(hoster.hosterUrl)
        return when {
            "dood" in url || "d-s.io" in url || "dsvplay" in url || "do7go" in url ->
                doodExtractor.streamsFromUrl(url, hoster.hosterName)
            "voe" in url || "tubelessceliolymph" in url || "simpulumlamerop" in url ->
                voeExtractor.streamsFromUrl(url, hoster.hosterName)
            "streamtape" in url || "stp" in url || "stape" in url ->
                streamTapeExtractor.streamsFromUrl(url, hoster.hosterName)
            "streamwish" in url || "sfastwish" in url || "wishembed" in url || "strwish" in url ->
                streamWishExtractor.streamsFromUrl(url, hoster.hosterName)
            "filemoon" in url || "moonplayer" in url || "files.im" in url ->
                filemoonExtractor.streamsFromUrl(url, prefix = hoster.hosterName)
            else ->
                universalExtractor.streamsFromUrl(url, headers, prefix = hoster.hosterName)
        }.sortStreams()
    }

    private fun decodeFkPlayerUrl(url: String): String {
        if (!url.contains("fkplayer.xyz")) return url
        return try {
            val token = url.substringAfterLast("/").substringBefore("?")
            val payload = token.split('.').getOrNull(1) ?: return url
            val encodedUrl = strictJson.parseToJsonElement(
                String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP)),
            )
                .jsonObject["link"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?: return url
            String(Base64.decode(encodedUrl, Base64.DEFAULT))
        } catch (_: Exception) {
            url
        }
    }

    override fun List<SStream>.sortStreams(): List<SStream> {
        val prefs = preferenceValues()
        val quality = prefs.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
        return sortedWith(
            compareBy(
                { it.title.contains(quality) },
                { Regex("""(\d+)p""").find(it.title)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    // endregion

    // region Filters & Preferences

    override fun getFilterList() = FilterList()

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

    private fun DoramaDto.toSContent(): SContent = SContent.create().apply {
        setUrlWithoutDomain("/doramas/${this@toSContent.slug}?_id=${this@toSContent.id}")
        title = this@toSContent.nameEs ?: this@toSContent.name
        description = this@toSContent.overview
        posterUrl = this@toSContent.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
        backdropUrl = this@toSContent.backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" }
        genres = this@toSContent.genres?.mapNotNull { it.name }
        status = ContentStatus.UNKNOWN
        contentType = ContentType.SERIES
        year = this@toSContent.firstAirDate?.take(4)?.toIntOrNull()
        rating = this@toSContent.voteAverage
    }

    private fun MovieDto.toSContent(): SContent = SContent.create().apply {
        setUrlWithoutDomain("/peliculas/${this@toSContent.slug}?_id=${this@toSContent.id}")
        title = this@toSContent.nameEs ?: this@toSContent.title ?: this@toSContent.name
        description = this@toSContent.overview
        posterUrl = this@toSContent.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
        backdropUrl = this@toSContent.backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" }
        genres = this@toSContent.genres?.mapNotNull { it.name }
        status = ContentStatus.UNKNOWN
        contentType = ContentType.MOVIE
        year = this@toSContent.releaseDate?.take(4)?.toIntOrNull()
        rating = this@toSContent.voteAverage
    }

    private fun extractHosterName(url: String, serverName: String = ""): String = when {
        serverName.isNotEmpty() -> serverName
        "dood" in url || "d-s.io" in url || "dsvplay" in url || "do7go" in url -> "DoodStream"
        "voe" in url || "tubelessceliolymph" in url || "simpulumlamerop" in url -> "Voe"
        "streamtape" in url || "stp" in url || "stape" in url -> "StreamTape"
        "streamwish" in url || "sfastwish" in url || "wishembed" in url || "strwish" in url -> "StreamWish"
        "filemoon" in url || "moonplayer" in url || "files.im" in url -> "FileMoon"
        "okru" in url || "ok.ru" in url -> "Okru"
        "mixdrop" in url || "mxdrop" in url -> "MixDrop"
        "vidhide" in url || "vidhidepre" in url -> "VidHide"
        "uqload" in url -> "UqLoad"
        "vudeo" in url -> "Vudeo"
        "bysefujedu" in url -> "Fembed"
        "primeload" in url -> "PrimeLoad"
        else -> url.substringAfter("://").substringBefore("/").substringBefore("?")
    }

    private fun getLang(langId: String?): String = when (langId) {
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

    // region Extractors

    private val doodExtractor by lazy { DoodExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    // endregion

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val qualityList = listOf("1080", "720", "480", "360")
    }
}
