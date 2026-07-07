package org.symera.mediasource.es.pelisplusph

import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
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
import org.symera.source.model.SourcePreference
import org.symera.source.online.GET
import org.symera.source.online.asJsoup
import org.symera.source.preferenceValues

class Pelisplusph : PelisPlus() {

    override val name = "PelisPlusPh"
    override val baseUrl = "https://www.pelisplushd.la"

    override fun moviesRequest(page: Int): Request = GET("$baseUrl/peliculas?page=$page", headers)

    override fun moviesParse(response: Response): ContentPage = parseListing(response)

    override fun seriesRequest(page: Int): Request = GET("$baseUrl/series?page=$page", headers)

    override fun seriesParse(response: Response): ContentPage = parseListing(response)

    override fun searchRequest(page: Int, query: String, filters: FilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        return when {
            query.isNotBlank() -> GET("$baseUrl/search?s=$query&page=$page", headers)
            genreFilter.state != 0 -> GET("$baseUrl/${genreFilter.toUriPart()}?page=$page", headers)
            else -> moviesRequest(page)
        }
    }

    override fun searchParse(response: Response): ContentPage = parseListing(response)

    private fun parseListing(response: Response): ContentPage {
        val document = response.asJsoup()
        val contents = document.select(".Posters-link").mapNotNull(::contentFromElement)
        return ContentPage(contents, contents.isNotEmpty())
    }

    private fun contentFromElement(element: Element): SContent? {
        val title = element.select(".listing-content > p").text().takeIf { it.isNotBlank() } ?: return null
        return SContent.create().apply {
            setUrlWithoutDomain(element.attr("abs:href"))
            this.title = title
            posterUrl = element.selectFirst("img")?.attr("abs:src")
            contentType = parseType(url)
        }
    }

    override fun contentDetailsParse(response: Response): SContent {
        val document = response.asJsoup()
        return SContent.create().apply {
            url = response.request.url.encodedPath
            title = document.selectFirst(".card-body h1")?.text().orEmpty()
            document.select(".card-body p").forEach { paragraph ->
                if (paragraph.text().contains("Sinopsis:")) {
                    description = paragraph.nextElementSibling()?.text()
                }
                if (paragraph.select(".content-type").text().contains("Géneros:")) {
                    genres = paragraph.select(".content-type-a a").map { it.text() }
                }
            }
            contentType = parseType(url)
            status = if (contentType == ContentType.SERIES) ContentStatus.UNKNOWN else ContentStatus.COMPLETED
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

        return document.select(".tab-content a").mapIndexed { index, element ->
            SPlayableItem.create().apply {
                setUrlWithoutDomain(element.attr("abs:href"))
                title = element.ownText()
                episodeNumber = (index + 1).toDouble()
            }
        }.reversed()
    }

    override fun hostersParse(response: Response): List<SHoster> {
        val document = response.asJsoup()
        return document.select(".TbVideoNv li").mapNotNull { videoItem ->
            val url = videoItem.attr("data-url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val lang = when {
                videoItem.attr("data-name").contains("Subtitulado", true) -> "[SUB]"
                videoItem.attr("data-name").contains("Latino", true) -> "[LAT]"
                else -> "[CAST]"
            }
            SHoster(
                hosterUrl = url,
                hosterName = videoItem.text().ifBlank { url },
                displayName = listOf(lang, videoItem.text()).filter { it.isNotBlank() }.joinToString(" "),
                internalData = lang,
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

    override fun List<SStream>.sortStreams(): List<SStream> {
        val preferences = preferenceValues()
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)
        val lang = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)
        return sortedWith(
            compareBy(
                { it.title.contains(lang) },
                { it.title.contains(server, true) },
                { it.title.contains(quality) },
                { Regex("""(\d+)p""").find(it.title)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun getSourcePreferences(): List<SourcePreference<*>> = listOf(
        SourcePreference.Select(
            key = PREF_LANGUAGE_KEY,
            title = "Preferred language",
            values = LANGUAGE_LIST.map { SourcePreference.Option(it) },
            summary = "%s",
            defaultValue = PREF_LANGUAGE_DEFAULT,
        ),
    ) + super.getSourcePreferences()

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("La búsqueda por género ignora los otros filtros"),
        GenreFilter(),
    )

    private fun parseType(url: String): ContentType = if (url.contains("/serie/")) ContentType.SERIES else ContentType.MOVIE

    private class GenreFilter :
        Filters.UriPartFilter(
            "Géneros",
            listOf(
                "<selecionar>" to "",
                "Peliculas" to "peliculas",
                "Series" to "series",
                "Estrenos" to "estrenos",
                "Acción" to "genero/accion",
                "Artes marciales" to "genero/artes-marciales",
                "Aventura" to "genero/aventura",
                "Bélico" to "genero/belico",
                "Ciencia Ficción" to "genero/ciencia-ficcion",
                "Comedia" to "genero/comedia",
                "Crimen" to "genero/crimen",
                "Documental" to "genero/documental",
                "Drama" to "genero/drama",
                "Familiar" to "genero/familiar",
                "Fantasía" to "genero/fantasia",
                "Historia" to "genero/historia",
                "Horror" to "genero/horror",
                "Infantil" to "genero/infantil",
                "Misterio" to "genero/misterio",
                "Música" to "genero/musica",
                "Romance" to "genero/romance",
                "Suspenso" to "genero/suspenso",
                "Terror" to "genero/terror",
                "Thriller" to "genero/thriller",
                "TV Series" to "genero/tv-series",
                "Western" to "genero/western",
                "Zombie" to "genero/zombie",
            ),
        )

    companion object {
        private const val PREF_LANGUAGE_KEY = "preferred_language"
        private const val PREF_LANGUAGE_DEFAULT = "[LAT]"
        private val LANGUAGE_LIST = listOf("[LAT]", "[SUB]", "[CAST]")
    }
}
