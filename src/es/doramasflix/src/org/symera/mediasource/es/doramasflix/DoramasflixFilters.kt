package org.symera.mediasource.es.doramasflix

import org.symera.source.model.Filter

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

class ContentTypeFilter : UriPartSelectFilter("Tipo", "content_type", DoramasflixFiltersData.CONTENT_TYPES)
class LanguageFilter : UriPartSelectFilter("Idioma", "language", DoramasflixFiltersData.LANGUAGES)

private object DoramasflixFiltersData {
    val CONTENT_TYPES = arrayOf(
        "<Todos>" to "",
        "Dorama" to "dorama",
        "Pelicula" to "pelicula",
    )

    val LANGUAGES = arrayOf(
        "<Todos>" to "",
        "[ENG] English" to "36",
        "[CAST] Castellano" to "37",
        "[LAT] Latino" to "38",
        "[SUB] Subtitulado" to "192",
        "[POR] Portugues" to "1327",
        "[COR] Coreano" to "13109",
        "[JAP] Japones" to "13110",
        "[MAN] Mandarín" to "13111",
        "[TAI] Tailandes" to "13112",
        "[FIL] Filipino" to "13113",
        "[IND] Indonesio" to "13114",
        "[VIET] Vietnamita" to "343422",
    )
}
