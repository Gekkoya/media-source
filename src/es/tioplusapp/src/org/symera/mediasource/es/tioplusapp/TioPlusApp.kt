package org.symera.mediasource.es.tioplusapp

import android.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import org.symera.mediasource.core.parseAs
import org.symera.mediasource.core.useAsJsoup
import org.symera.mediasource.multisrc.pelisplus.Filters
import org.symera.mediasource.multisrc.pelisplus.PelisPlus
import org.symera.source.model.ContentPage
import org.symera.source.model.ContentStatus
import org.symera.source.model.ContentType
import org.symera.source.model.Filter
import org.symera.source.model.FilterList
import org.symera.source.model.SContent
import org.symera.source.model.SHoster
import org.symera.source.model.SPlayableItem
import org.symera.source.model.SStream
import org.symera.source.online.GET
import org.symera.source.online.asJsoup

class TioPlusApp : PelisPlus() {

    override val name = "TioPlusApp"
    override val baseUrl = "https://tioplus.app"

    override fun moviesRequest(page: Int): Request = GET("$baseUrl/peliculas?page=$page", headers)

    override fun moviesParse(response: Response): ContentPage = parseListing(response)

    override fun seriesRequest(page: Int): Request = GET("$baseUrl/series?page=$page", headers)

    override fun seriesParse(response: Response): ContentPage = parseListing(response)

    override fun searchRequest(page: Int, query: String, filters: FilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        return when {
            query.isNotBlank() -> GET("$baseUrl/api/search/$query", headers)
            genreFilter.state != 0 -> GET("$baseUrl/${genreFilter.toUriPart()}?page=$page", headers)
            else -> moviesRequest(page)
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
        return SContent.create().apply {
            setUrlWithoutDomain(link.attr("abs:href"))
            this.title = title
            posterUrl = element.selectFirst("a .item__image picture img")?.attr("abs:data-src")
                ?: element.selectFirst("a .item__image picture img")?.attr("abs:src")
            contentType = parseType(url)
        }
    }

    override fun contentDetailsParse(response: Response): SContent {
        val document = response.asJsoup()
        return SContent.create().apply {
            url = response.request.url.encodedPath
            title = document.selectFirst(".home__slider_content div h1.slugh1")?.text().orEmpty()
            description = document.selectFirst(".home__slider_content .description")?.text()
            genres = document.select(".home__slider_content div:nth-child(5) > a").map { it.text() }
            status = ContentStatus.COMPLETED
            contentType = parseType(url)
        }
    }

    override fun playableItemsParse(response: Response): List<SPlayableItem> {
        val document = response.asJsoup()
        val contentUrl = response.request.url.toString().trimEnd('/')
        if (contentUrl.contains("/pelicula/")) {
            return listOf(
                SPlayableItem.create().apply {
                    setUrlWithoutDomain(contentUrl)
                    title = "PELÍCULA"
                    episodeNumber = 1.0
                },
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
                SPlayableItem.create().apply {
                    setUrlWithoutDomain("$contentUrl/season/$season/episode/$episodeNumber")
                    this.title = "T$season - E$episodeNumber - $title"
                    this.episodeNumber = index.toDouble()
                    seasonNumber = season.toDoubleOrNull()
                }
            }
        }.reversed()
    }

    override fun hostersParse(response: Response): List<SHoster> {
        val document = response.useAsJsoup()
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
                val script = client.newCall(GET(url, headers)).execute().useAsJsoup()
                    .selectFirst("script:containsData(window.onload)")?.data().orEmpty()
                fetchUrls(script).firstOrNull().orEmpty()
            } else {
                url
            }.replace("https://sblanh.com", "https://lvturbo.com")
                .replace(Regex("([a-zA-Z0-9]{0,8}[a-zA-Z0-9_-]+)=https://ww3.pelisplus.to.*"), "")

            videoUrl.takeIf { it.isNotBlank() }?.toHoster(prefix)
        }
    }

    override suspend fun getStreams(hoster: SHoster): List<SStream> = serverVideoResolver(
        url = hoster.hosterUrl,
        prefix = hoster.internalData,
        serverName = hoster.hosterName,
    ).sortStreams()

    override fun streamsParse(response: Response, hoster: SHoster): List<SStream> = emptyList()

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("La búsqueda por género ignora los otros filtros"),
        GenreFilter(),
    )

    private fun String.toHoster(prefix: String): SHoster = SHoster(
        hosterUrl = this,
        hosterName = this,
        displayName = listOf(prefix, this).filter { it.isNotBlank() }.joinToString(" "),
        internalData = prefix,
        lazy = true,
    )

    private fun parseType(url: String): ContentType = if (url.contains("/serie/")) ContentType.SERIES else ContentType.MOVIE

    private class GenreFilter :
        Filters.UriPartFilter(
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
        )
}
