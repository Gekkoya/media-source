package org.symera.mediasource.es.tioplusapp

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import org.symera.mediasource.core.parseAs
import org.symera.mediasource.lib.emturbo.EmTurboExtractor
import org.symera.mediasource.multisrc.pelisplus.PelisPlus
import org.symera.source.CatalogCapability
import org.symera.source.CatalogFeed
import org.symera.source.SourceCapability
import org.symera.source.SourceEnvironment
import org.symera.source.SymeraExtensionFactory
import org.symera.source.model.ContentPage
import org.symera.source.model.ContentStatus
import org.symera.source.model.ContentStructure
import org.symera.source.model.ContentType
import org.symera.source.model.Filter
import org.symera.source.model.FilterList
import org.symera.source.model.PageRequest
import org.symera.source.model.PlayableItemType
import org.symera.source.model.SContent
import org.symera.source.model.SHoster
import org.symera.source.model.SPlayableItem
import org.symera.source.model.SSeason
import org.symera.source.model.SStream
import org.symera.source.online.GET
import org.symera.source.online.asJsoup

class TioPlusApp(environment: SourceEnvironment) : PelisPlus(environment) {

    override val name = "TioPlusApp"
    override val baseUrl = "https://tioplus.app"
    override val lang = "es"
    override val contentTypes = setOf(ContentType.MOVIE, ContentType.SERIES)
    override val catalogCapabilities = setOf(CatalogCapability.MOVIES, CatalogCapability.SERIES, CatalogCapability.SEARCH)
    override val sourceCapabilities = setOf(SourceCapability.PLAYABLE_ITEMS, SourceCapability.SEASONS, SourceCapability.HOSTERS)

    override fun headersBuilder() = super.headersBuilder()
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
        .add("Referer", "$baseUrl/")

    override val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    override fun moviesRequest(request: PageRequest, filters: FilterList): Request = GET(pagedUrl("peliculas", request.page), headers)

    override fun moviesParse(response: Response): ContentPage = parseListing(response)

    override fun seriesRequest(request: PageRequest, filters: FilterList): Request {
        val section = filters.filterIsInstance<SeriesSectionFilter>().firstOrNull()?.toUriPart() ?: "series"
        return GET(pagedUrl(section, request.page), headers)
    }

    override fun seriesParse(response: Response): ContentPage = parseListing(response)

    override fun searchRequest(request: PageRequest, query: String, filters: FilterList): Request {
        val genreFilter = filters.filterIsInstance<GenreFilter>().firstOrNull() ?: GenreFilter()
        return when {
            query.isNotBlank() -> GET(
                baseUrl.toHttpUrl().newBuilder()
                    .addPathSegment("api")
                    .addPathSegment("search")
                    .addPathSegment(query)
                    .addQueryParameter("page", request.page.toString())
                    .build(),
                headers,
            )
            genreFilter.state != 0 -> GET("$baseUrl/${genreFilter.toUriPart()}?page=${request.page}", headers)
            else -> moviesRequest(request, filters)
        }
    }

    override fun searchParse(response: Response): ContentPage = parseListing(response)

    private fun parseListing(response: Response): ContentPage {
        val document = response.asJsoup()
        val contents = document.select("article.item").mapNotNull(::contentFromElement)
        val hasNextPage = document.selectFirst("a[rel=next]") != null
        return ContentPage(contents, hasNextPage)
    }

    private fun pagedUrl(section: String, page: Int): String = "$baseUrl/$section" + if (page > 1) "/$page" else ""

    private fun contentFromElement(element: Element): SContent? {
        val link = element.selectFirst("a") ?: return null
        val title = element.selectFirst("a h2")?.text()?.takeIf { it.isNotBlank() } ?: return null
        return SContent(
            url = relativeUrl(link.attr("abs:href")),
            title = title,
            posterUrl = element.selectFirst("a .item__image picture img")?.attr("abs:data-src")
                ?: element.selectFirst("a .item__image picture img")?.attr("abs:src"),
            contentType = parseType(link.attr("abs:href")),
            structure = structureFor(parseType(link.attr("abs:href"))),
        )
    }

    override fun contentDetailsParse(response: Response): SContent {
        val document = response.asJsoup()
        val url = relativeUrl(response.request.url.toString())
        return SContent(
            url = url,
            title = document.selectFirst(".home__slider_content div h1.slugh1")?.text()?.ifBlank { null } ?: "Unknown",
            description = document.selectFirst(".home__slider_content .description")?.text(),
            posterUrl = document.selectFirst("meta[property=og:image]")?.attr("abs:content")?.ifBlank { null },
            backdropUrl = document.selectFirst(".home__slider .bg")?.attr("style")
                ?.substringAfter("url(\"")
                ?.substringBefore("\")")
                ?.ifBlank { null },
            genres = document.select(".home__slider_content .genres")
                .firstOrNull { it.text().contains("Generos", ignoreCase = true) }
                ?.select("a")
                ?.map { it.text() }
                .orEmpty(),
            status = ContentStatus.COMPLETED,
            contentType = parseType(url),
            structure = structureFor(parseType(url)),
        )
    }

    override fun playableItemsParse(response: Response): List<SPlayableItem> {
        val document = response.asJsoup()
        val contentUrl = response.request.url.toString().trimEnd('/')
        if (contentUrl.contains("/pelicula/")) {
            return listOf(
                SPlayableItem(url = relativeUrl(contentUrl), title = "PELÍCULA", type = PlayableItemType.MOVIE),
            )
        }

        return parseSeasons(document, relativeUrl(contentUrl)).flatMap { it.playableItems.orEmpty() }
    }

    override fun seasonsParse(response: Response): List<SSeason> = parseSeasons(response.asJsoup(), relativeUrl(response.request.url.toString().trimEnd('/')))

    private fun parseSeasons(document: org.jsoup.nodes.Document, contentUrl: String): List<SSeason> {
        val seasonsData = document.selectFirst("script:containsData(const seasonUrl =)")?.data() ?: return emptyList()
        val seasonsJson = seasonsData.substringAfter("seasonsJson = ").substringBefore(";")
        val seasons = seasonsJson.parseAs<JsonObject>(json)
        return seasons.entries.mapNotNull { (seasonKey, episodes) ->
            val season = seasonKey.toIntOrNull() ?: return@mapNotNull null
            val playableItems = episodes.jsonArray.mapNotNull { element ->
                val episode = element.jsonObject
                val episodeNumber = episode["episode"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val title = episode["title"]?.jsonPrimitive?.content.orEmpty()
                SPlayableItem(
                    url = "$contentUrl/season/$season/episode/$episodeNumber",
                    title = "T$season - E$episodeNumber - $title",
                    type = PlayableItemType.EPISODE,
                    episodeNumber = org.symera.source.model.EpisodeNumber(episodeNumber),
                    seasonNumber = season,
                    thumbnailUrl = episode["image"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                        ?.let { image ->
                            if (image.startsWith("http://") || image.startsWith("https://")) {
                                image
                            } else {
                                "https://image.tmdb.org/t/p/w342$image"
                            }
                        },
                )
            }
            SSeason(
                url = "$contentUrl/season/$season",
                number = season,
                title = "Temporada $season",
                playableItems = playableItems,
            )
        }.sortedBy { it.number }
    }

    override fun hostersParse(response: Response): List<SHoster> {
        val document = response.use { it.asJsoup() }
        return document.select(".bg-tabs ul li").mapNotNull { element ->
            val prefix = element.parent()?.parent()?.selectFirst("button")?.ownText()?.lowercase()?.getLang().orEmpty()
            val serverName = element.selectFirst("span")?.text()?.takeIf { it.isNotBlank() }.orEmpty()
            val encodedServer = element.attr("data-server").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val decoded = String(Base64.decode(encodedServer, Base64.DEFAULT))

            val url = if (REGEX_LINK.containsMatchIn(decoded)) {
                decoded
            } else {
                val encoded = Base64.encodeToString(encodedServer.toByteArray(), Base64.NO_WRAP)
                "$baseUrl/player/$encoded"
            }

            val videoUrl = if (url.contains("/player/")) {
                val script = client.newCall(GET(url, headers)).execute().use { it.asJsoup() }
                    .selectFirst("script:containsData(window.onload)")?.data().orEmpty()
                fetchUrls(script).firstOrNull().orEmpty()
            } else {
                url
            }.replace("https://sblanh.com", "https://lvturbo.com")
                .replace(Regex("([a-zA-Z0-9]{0,8}[a-zA-Z0-9_-]+)=https://ww3.pelisplus.to.*"), "")

            videoUrl
                .takeIf { it.isNotBlank() && UNSUPPORTED_P2P_HOSTS.none { host -> host in it } }
                ?.toHoster(prefix, serverName)
        }
    }

    override suspend fun getStreams(hoster: SHoster): List<SStream> {
        val url = hoster.requestUrl ?: return emptyList()
        return if ("emturbovid" in url) {
            emTurboExtractor.streamsFromUrl(url, hoster.name)
        } else {
            serverVideoResolver(
                url = url,
                prefix = hoster.resolverData.orEmpty(),
                serverName = hoster.name,
            )
        }.sortStreams()
    }

    override fun getFilterList(feed: CatalogFeed): FilterList = when (feed) {
        CatalogFeed.SEARCH -> FilterList(Filter.Header("La búsqueda por género ignora los otros filtros"), GenreFilter())
        CatalogFeed.SERIES -> FilterList(SeriesSectionFilter())
        else -> FilterList()
    }

    private fun String.toHoster(prefix: String, serverName: String): SHoster = SHoster(
        id = this,
        name = listOf(prefix, serverName).filter { it.isNotBlank() }.joinToString(" "),
        requestUrl = this,
        resolverData = prefix,
    )

    private fun parseType(url: String): ContentType = if (
        url.contains("/serie/") || url.contains("/dorama/") || url.contains("/anime/")
    ) {
        ContentType.SERIES
    } else {
        ContentType.MOVIE
    }

    private fun structureFor(type: ContentType): ContentStructure = when (type) {
        ContentType.MOVIE -> ContentStructure.SINGLE_ITEM
        ContentType.SERIES -> ContentStructure.SEASONS
        else -> ContentStructure.UNKNOWN
    }

    private class GenreFilter :
        Filter.Select<Pair<String, String>>(
            "Géneros",
            listOf(
                "<selecionar>" to "",
                "Peliculas" to "peliculas",
                "Series" to "series",
                "Doramas" to "doramas",
                "Animes" to "animes",
                "Acción" to "genres/accion",
                "Action & Adventure" to "genres/action-adventure",
                "Animación" to "genres/animacion",
                "Aventura" to "genres/aventura",
                "Bélica" to "genres/belica",
                "Ciencia ficción" to "genres/ciencia-ficcion",
                "Comedia" to "genres/comedia",
                "Crimen" to "genres/crimen",
                "Documental" to "genres/documental",
                "Dorama" to "genres/dorama",
                "Drama" to "genres/drama",
                "Familia" to "genres/familia",
                "Fantasía" to "genres/fantasia",
                "Guerra" to "genres/guerra",
                "Historia" to "genres/historia",
                "Horror" to "genres/horror",
                "Kids" to "genres/kids",
                "Misterio" to "genres/misterio",
                "Música" to "genres/musica",
                "Musical" to "genres/musical",
                "Película de TV" to "genres/pelicula-de-tv",
                "Reality" to "genres/reality",
                "Romance" to "genres/romance",
                "Sci-Fi & Fantasy" to "genres/sci-fi-fantasy",
                "Soap" to "genres/soap",
                "Suspense" to "genres/suspense",
                "Terror" to "genres/terror",
                "War & Politics" to "genres/war-politics",
                "Western" to "genres/western",
            ),
        ) {
        fun toUriPart() = values[state].second
    }

    private class SeriesSectionFilter : Filter.Select<String>("Sección", listOf("Series", "Doramas", "Animes")) {
        fun toUriPart() = values[state].lowercase()
    }

    private companion object {
        val UNSUPPORTED_P2P_HOSTS = setOf("strp2p.com", "upns.pro", "4meplayer.pro")
    }

    private val emTurboExtractor by lazy { EmTurboExtractor(client, headers) }
}

object TioPlusAppFactory : SymeraExtensionFactory {
    override fun createVodSources(environment: SourceEnvironment) = listOf(TioPlusApp(environment))
}
