package org.symera.mediasource.es.tioplusapp

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import org.symera.mediasource.core.parseAs
import org.symera.mediasource.multisrc.pelisplus.PelisPlus
import org.symera.source.CatalogCapability
import org.symera.source.CatalogFeed
import org.symera.source.SourceCapability
import org.symera.source.SourceEnvironment
import org.symera.source.SymeraExtensionFactory
import org.symera.source.model.ContentPage
import org.symera.source.model.ContentStatus
import org.symera.source.model.ContentType
import org.symera.source.model.Filter
import org.symera.source.model.FilterList
import org.symera.source.model.PageRequest
import org.symera.source.model.PlayableItemType
import org.symera.source.model.SContent
import org.symera.source.model.SHoster
import org.symera.source.model.SPlayableItem
import org.symera.source.model.SStream
import org.symera.source.online.GET
import org.symera.source.online.asJsoup

class TioPlusApp(environment: SourceEnvironment) : PelisPlus(environment) {

    override val name = "TioPlusApp"
    override val baseUrl = "https://tioplus.app"
    override val lang = "es"
    override val contentTypes = setOf(ContentType.MOVIE, ContentType.SERIES)
    override val catalogCapabilities = setOf(CatalogCapability.MOVIES, CatalogCapability.SERIES, CatalogCapability.SEARCH)
    override val sourceCapabilities = setOf(SourceCapability.PLAYABLE_ITEMS, SourceCapability.HOSTERS)

    override fun headersBuilder() =
        super.headersBuilder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .add("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
            .add("Referer", "$baseUrl/")

    override val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    override fun moviesRequest(request: PageRequest, filters: FilterList): Request = GET("$baseUrl/peliculas?page=${request.page}", headers)

    override fun moviesParse(response: Response): ContentPage = parseListing(response)

    override fun seriesRequest(request: PageRequest, filters: FilterList): Request = GET("$baseUrl/series?page=${request.page}", headers)

    override fun seriesParse(response: Response): ContentPage = parseListing(response)

    override fun searchRequest(request: PageRequest, query: String, filters: FilterList): Request {
        val genreFilter = filters.filterIsInstance<GenreFilter>().firstOrNull() ?: GenreFilter()
        return when {
            query.isNotBlank() -> GET("$baseUrl/api/search/$query", headers)
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

    private fun contentFromElement(element: Element): SContent? {
        val link = element.selectFirst("a") ?: return null
        val title = element.selectFirst("a h2")?.text()?.takeIf { it.isNotBlank() } ?: return null
        return SContent(
            url = relativeUrl(link.attr("abs:href")),
            title = title,
            posterUrl = element.selectFirst("a .item__image picture img")?.attr("abs:data-src")
                ?: element.selectFirst("a .item__image picture img")?.attr("abs:src"),
            contentType = parseType(link.attr("abs:href")),
        )
    }

    override fun contentDetailsParse(response: Response): SContent {
        val document = response.asJsoup()
        val url = relativeUrl(response.request.url.toString())
        return SContent(
            url = url,
            title = document.selectFirst(".home__slider_content div h1.slugh1")?.text()?.ifBlank { null } ?: "Unknown",
            description = document.selectFirst(".home__slider_content .description")?.text(),
            genres = document.select(".home__slider_content div:nth-child(5) > a").map { it.text() },
            status = ContentStatus.COMPLETED,
            contentType = parseType(url),
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

        val seasonsData = document.selectFirst("script:containsData(const seasonUrl =)")?.data() ?: return emptyList()
        val seasonsJson = seasonsData.substringAfter("seasonsJson = ").substringBefore(";")
        val seasons = seasonsJson.parseAs<JsonObject>(json)
        var index = 0
        return seasons.entries.flatMap { (_, episodes) ->
            episodes.jsonArray.reversed().map { element ->
                index += 1
                val episode = element.jsonObject
                val season = episode["season"]?.jsonPrimitive?.content.orEmpty()
                val title = episode["title"]?.jsonPrimitive?.content.orEmpty()
                val episodeNumber = episode["episode"]?.jsonPrimitive?.content.orEmpty()
                SPlayableItem(
                    url = relativeUrl("$contentUrl/season/$season/episode/$episodeNumber"),
                    title = "T$season - E$episodeNumber - $title",
                    type = PlayableItemType.EPISODE,
                    episodeNumber = org.symera.source.model.EpisodeNumber(index),
                    seasonNumber = season.toIntOrNull(),
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
        }.reversed()
    }

    override fun hostersParse(response: Response): List<SHoster> {
        val document = response.use { it.asJsoup() }
        return document.select(".bg-tabs ul li").mapNotNull { element ->
            val prefix = element.parent()?.parent()?.selectFirst("button")?.ownText()?.lowercase()?.getLang().orEmpty()
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

            videoUrl.takeIf { it.isNotBlank() }?.toHoster(prefix)
        }
    }

    override suspend fun getStreams(hoster: SHoster): List<SStream> {
        val url = hoster.requestUrl ?: return emptyList()
        return serverVideoResolver(
            url = url,
            prefix = hoster.resolverData.orEmpty(),
            serverName = hoster.name,
        ).sortStreams()
    }

    override fun getFilterList(feed: CatalogFeed): FilterList = if (feed == CatalogFeed.SEARCH) {
        FilterList(Filter.Header("La búsqueda por género ignora los otros filtros"), GenreFilter())
    } else {
        FilterList()
    }

    private fun String.toHoster(prefix: String): SHoster = SHoster(
        id = this,
        name = listOf(prefix, this).filter { it.isNotBlank() }.joinToString(" "),
        requestUrl = this,
        resolverData = prefix,
    )

    private fun parseType(url: String): ContentType = if (url.contains("/serie/")) ContentType.SERIES else ContentType.MOVIE

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
}

object TioPlusAppFactory : SymeraExtensionFactory {
    override fun createVodSources(environment: SourceEnvironment) = listOf(TioPlusApp(environment))
}
