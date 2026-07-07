package org.symera.mediasource.es.pelisplushd

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import org.symera.mediasource.core.flatMapCatching
import org.symera.mediasource.core.parseAs
import org.symera.mediasource.core.toJsonRequestBody
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
import org.symera.source.online.POST
import org.symera.source.online.asJsoup

class Pelisplushd : PelisPlus() {

    override val name = "PelisPlusHD"
    override val baseUrl = "https://pelisplushd.bz"

    override fun moviesRequest(page: Int): Request = GET("$baseUrl/peliculas?page=$page", headers)

    override fun moviesParse(response: Response): ContentPage = parseListing(response)

    override fun seriesRequest(page: Int): Request = GET("$baseUrl/series?page=$page", headers)

    override fun seriesParse(response: Response): ContentPage = parseListing(response)

    override fun searchRequest(page: Int, query: String, filters: FilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        val yearFilter = filterList.find { it is YearFilter } as YearFilter
        return when {
            query.isNotBlank() -> GET("$baseUrl/search?s=$query&page=$page", headers)
            genreFilter.state != 0 -> GET("$baseUrl/${genreFilter.toUriPart()}?page=$page", headers)
            yearFilter.state.isNotBlank() -> GET("$baseUrl/year/${yearFilter.state}?page=$page", headers)
            else -> moviesRequest(page)
        }
    }

    override fun searchParse(response: Response): ContentPage = parseListing(response)

    private fun parseListing(response: Response): ContentPage {
        val document = response.asJsoup()
        val contents = document.select("div.Posters a.Posters-link").mapNotNull(::contentFromElement)
        val hasNextPage = document.selectFirst("a.page-link") != null
        return ContentPage(contents, hasNextPage)
    }

    private fun contentFromElement(element: Element): SContent? {
        val title = element.select("a div.listing-content p").text().takeIf { it.isNotBlank() } ?: return null
        return SContent.create().apply {
            setUrlWithoutDomain(element.attr("abs:href"))
            this.title = title
            posterUrl = element.selectFirst("a img")?.attr("abs:src")?.replace("/w154/", "/w200/")
            contentType = parseType(url)
        }
    }

    override fun contentDetailsParse(response: Response): SContent {
        val document = response.asJsoup()
        return SContent.create().apply {
            url = response.request.url.encodedPath
            title = document.selectFirst("h1.m-b-5")?.text().orEmpty()
            posterUrl = document.selectFirst("div.card-body div.row div.col-sm-3 img.img-fluid")
                ?.attr("abs:src")
                ?.replace("/w154/", "/w500/")
            description = document.selectFirst("div.col-sm-4 div.text-large")?.ownText()
            genres = document.select("div.p-v-20.p-h-15.text-center a span").map { it.text() }
            status = ContentStatus.COMPLETED
            contentType = parseType(url)
        }
    }

    override fun playableItemsParse(response: Response): List<SPlayableItem> {
        val document = response.asJsoup()
        val contentUrl = response.request.url.toString()
        if (contentUrl.contains("/pelicula/")) {
            return listOf(
                SPlayableItem.create().apply {
                    setUrlWithoutDomain(contentUrl)
                    title = "PELÍCULA"
                    episodeNumber = 1.0
                },
            )
        }

        return document.select("div.tab-content div a").mapIndexed { index, element ->
            SPlayableItem.create().apply {
                setUrlWithoutDomain(element.attr("abs:href"))
                title = element.text()
                episodeNumber = (index + 1).toDouble()
            }
        }.reversed()
    }

    override fun hostersParse(response: Response): List<SHoster> {
        val document = response.asJsoup()
        val data = document.selectFirst("script:containsData(video[1] = )")?.data() ?: return emptyList()

        return REGEX_VIDEO_OPTS.findAll(data).map { it.groupValues[1] }
            .filter { it.contains("embed69.org") }
            .toList()
            .flatMapCatching(::hostersFromEmbed69)
    }

    private fun hostersFromEmbed69(url: String): List<SHoster> {
        val document = client.newCall(GET(url, headers)).execute().useAsJsoup()
        val cryptoScript = document.selectFirst("script:containsData(let dataLink)")?.data()
        if (!cryptoScript.isNullOrBlank()) {
            val dataLinks = (cryptoScript.substringAfter("let dataLink =").substringBefore("];") + "]").parseAs<List<DataLinkDto>>(json)
            return dataLinks.flatMapCatching { data ->
                val sortedEmbeds = data.sortedEmbeds
                val links = sortedEmbeds.mapNotNull { it?.link }
                val payload = buildJsonObject {
                    putJsonArray("links") {
                        links.forEach { add(it) }
                    }
                }.toJsonRequestBody(json)

                val decryptedLinks = client.newCall(POST("https://embed69.org/api/decrypt", headers = headers, body = payload))
                    .execute()
                    .parseAs<Embed69Dto>(json)
                    .links

                decryptedLinks.mapNotNull { decrypted ->
                    val link = decrypted.link.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val embed = sortedEmbeds.getOrNull(decrypted.index)
                    SHoster(
                        hosterUrl = link,
                        hosterName = embed?.servername ?: "Embed69",
                        displayName = listOf(data.videoLanguage, embed?.servername).filterNotNull().filter { it.isNotBlank() }.joinToString(" "),
                        internalData = data.videoLanguage.orEmpty(),
                        lazy = true,
                    )
                }
            }
        }

        return document.select("li[onclick]")
            .flatMap { fetchUrls(it.attr("onclick")) }
            .map { url ->
                SHoster(
                    hosterUrl = url,
                    hosterName = url,
                    displayName = url,
                    lazy = true,
                )
            }
    }

    override suspend fun getStreams(hoster: SHoster): List<SStream> = serverVideoResolver(
        url = hoster.hosterUrl,
        prefix = hoster.internalData,
        serverName = hoster.hosterName,
    ).sortStreams()

    override fun streamsParse(response: Response, hoster: SHoster): List<SStream> = emptyList()

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("La búsqueda por texto ignora el filtro de año"),
        GenreFilter(),
        Filter.Header("Búsqueda por año"),
        YearFilter("Año"),
    )

    private fun parseType(url: String): ContentType = if (url.contains("/serie/")) ContentType.SERIES else ContentType.MOVIE

    private class GenreFilter :
        Filters.UriPartFilter(
            "Géneros",
            listOf(
                "<selecionar>" to "",
                "Peliculas" to "peliculas",
                "Series" to "series",
                "Doramas" to "generos/dorama",
                "Animes" to "animes",
                "Acción" to "generos/accion",
                "Animación" to "generos/animacion",
                "Aventura" to "generos/aventura",
                "Ciencia Ficción" to "generos/ciencia-ficcion",
                "Comedia" to "generos/comedia",
                "Crimen" to "generos/crimen",
                "Documental" to "generos/documental",
                "Drama" to "generos/drama",
                "Fantasía" to "generos/fantasia",
                "Foreign" to "generos/foreign",
                "Guerra" to "generos/guerra",
                "Historia" to "generos/historia",
                "Misterio" to "generos/misterio",
                "Pelicula de Televisión" to "generos/pelicula-de-la-television",
                "Romance" to "generos/romance",
                "Suspense" to "generos/suspense",
                "Terror" to "generos/terror",
                "Western" to "generos/western",
            ),
        )

    private class YearFilter(name: String) : Filter.Text(name)

    @Serializable
    data class DataLinkDto(
        @SerialName("video_language")
        val videoLanguage: String? = null,
        @SerialName("sortedEmbeds")
        val sortedEmbeds: List<SortedEmbedsDto?> = emptyList(),
    )

    @Serializable
    data class SortedEmbedsDto(
        val link: String? = null,
        val type: String? = null,
        val servername: String? = null,
    )

    @Serializable
    data class Embed69Dto(
        val success: Boolean,
        val links: List<Embed69Links>,
    )

    @Serializable
    data class Embed69Links(
        val index: Int,
        val link: String,
    )

    companion object {
        private val REGEX_VIDEO_OPTS = "'(https?://[^']*)'".toRegex()
    }
}
