package org.symera.mediasource.es.jkanime

import org.symera.source.model.Filter
import java.util.Calendar

interface UriPartFilterInterface {
    fun toQueryParam(): Pair<String, String>?
}

open class SelectFilter(
    displayName: String,
    val vals: Array<Pair<String, String>>,
) : Filter.Select<String>(displayName, vals.map { it.first }) {
    fun toValue() = vals[state].second
}

open class UriPartSelectFilter(
    displayName: String,
    val keyName: String,
    vals: Array<Pair<String, String>>,
    val includeZero: Boolean = false,
) : SelectFilter(displayName, vals),
    UriPartFilterInterface {
    override fun toQueryParam(): Pair<String, String>? {
        val value = toValue()
        return value.takeIf { it.isNotBlank() && (includeZero || state != 0) }?.let { keyName to it }
    }
}

class GenreFilter : UriPartSelectFilter("Género", "genero", JkanimeFiltersData.GENRES)
class LetterFilter : UriPartSelectFilter("Letra", "letra", JkanimeFiltersData.LETTER)
class DemographyFilter : UriPartSelectFilter("Demografía", "demografia", JkanimeFiltersData.DEMOGRAPHY)
class CategoryFilter : UriPartSelectFilter("Categoría", "categoria", JkanimeFiltersData.CATEGORY)
class TypeFilter : UriPartSelectFilter("Tipo", "tipo", JkanimeFiltersData.TYPES)
class StateFilter : UriPartSelectFilter("Estado", "estado", JkanimeFiltersData.STATE)
class YearFilter : UriPartSelectFilter("Año", "fecha", JkanimeFiltersData.YEARS)
class SeasonFilter : UriPartSelectFilter("Temporada", "temporada", JkanimeFiltersData.SEASONS)
class OrderByFilter : UriPartSelectFilter("Ordenar Por", "filtro", JkanimeFiltersData.SORT_BY)
class SortModifiers : UriPartSelectFilter("Orden", "orden", JkanimeFiltersData.SORT, includeZero = true)

class DayFilter : SelectFilter("Día de emisión", JkanimeFiltersData.DAYS)

private object JkanimeFiltersData {
    val GENRES = arrayOf(
        "<Seleccionar>" to "",
        "Accion" to "accion",
        "Aventura" to "aventura",
        "Autos" to "autos",
        "Comedia" to "comedia",
        "Dementia" to "dementia",
        "Demonios" to "demonios",
        "Misterio" to "misterio",
        "Drama" to "drama",
        "Ecchi" to "ecchi",
        "Fantasia" to "fantasia",
        "Juegos" to "juegos",
        "Hentai" to "hentai",
        "Historico" to "historico",
        "Terror" to "terror",
        "Niños" to "nios",
        "Magia" to "magia",
        "Artes Marciales" to "artes-marciales",
        "Mecha" to "mecha",
        "Musica" to "musica",
        "Parodia" to "parodia",
        "Samurai" to "samurai",
        "Romance" to "romance",
        "Colegial" to "colegial",
        "Sci-Fi" to "sci-fi",
        "Shoujo" to "shoujo",
        "Shoujo Ai" to "shoujo-ai",
        "Shounen" to "shounen",
        "Shounen Ai" to "shounen-ai",
        "Space" to "space",
        "Deportes" to "deportes",
        "Super Poderes" to "super-poderes",
        "Vampiros" to "vampiros",
        "Yaoi" to "yaoi",
        "Yuri" to "yuri",
        "Harem" to "harem",
        "Cosas de la vida" to "cosas-de-la-vida",
        "Sobrenatural" to "sobrenatural",
        "Militar" to "militar",
        "Policial" to "policial",
        "Psicologico" to "psicologico",
        "Thriller" to "thriller",
        "Seinen" to "seinen",
        "Josei" to "josei",
        "Español Latino" to "latino",
        "Isekai" to "isekai",
    )

    val DAYS = arrayOf(
        "<Seleccionar>" to "",
        "Lunes" to "Lunes",
        "Martes" to "Martes",
        "Miércoles" to "Miércoles",
        "Jueves" to "Jueves",
        "Viernes" to "Viernes",
        "Sábado" to "Sábado",
        "Domingo" to "Domingo",
    )

    val LETTER = arrayOf("Todos" to "") + ('A'..'Z').map { "$it" to "$it" }.toTypedArray()
    val DEMOGRAPHY = arrayOf("Todos" to "", "Niños" to "nios", "Shoujo" to "shoujo", "Shounen" to "shounen", "Seinen" to "seinen", "Josei" to "josei")
    val CATEGORY = arrayOf("Todos" to "", "Donghua" to "donghua", "Latino" to "latino")
    val TYPES = arrayOf("<Seleccionar>" to "", "Animes" to "animes", "Películas" to "peliculas", "Especiales" to "especiales", "OVAS" to "ovas", "ONAS" to "onas")
    val STATE = arrayOf("<Cualquiera>" to "", "En Emisión" to "emision", "Finalizado" to "finalizados", "Por Estrenar" to "estrenos")
    val YEARS = arrayOf("Todos" to "") + (1981..Calendar.getInstance().get(Calendar.YEAR)).map { "$it" to "$it" }.reversed().toTypedArray()
    val SEASONS = arrayOf("<Cualquiera>" to "", "Primavera" to "primavera", "Verano" to "verano", "Otoño" to "otoño", "Invierno" to "invierno")
    val SORT_BY = arrayOf("Por fecha" to "", "Por nombre" to "nombre", "Por popularidad" to "popularidad")
    val SORT = arrayOf("Descendente" to "", "Ascendente" to "asc")
}
