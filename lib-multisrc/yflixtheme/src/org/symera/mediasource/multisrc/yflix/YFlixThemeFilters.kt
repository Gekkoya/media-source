package org.symera.mediasource.multisrc.yflix

import okhttp3.HttpUrl
import org.symera.source.model.Filter
import org.symera.source.model.FilterList

object YFlixThemeFilters {
    fun getFilters(filters: FilterList): List<YFlixFilter> = filters.filterIsInstance<YFlixFilter>()

    interface YFlixFilter {
        fun addQueryParameters(builder: HttpUrl.Builder)
    }

    internal open class QueryPartFilter(
        displayName: String,
        private val param: String,
        private val options: List<Pair<String, String>>,
    ) : Filter.Select<String>(displayName, options.map { it.first }),
        YFlixFilter {
        override fun addQueryParameters(builder: HttpUrl.Builder) {
            if (state > 0) {
                builder.addQueryParameter(param, options[state].second)
            }
        }
    }

    internal open class MultiSelectFilter(
        name: String,
        private val param: String,
        private val options: List<Pair<String, String>>,
    ) : Filter.MultiSelect<String>(name, options.map { it.first }),
        YFlixFilter {
        override fun addQueryParameters(builder: HttpUrl.Builder) {
            state.forEach { index ->
                options.getOrNull(index)?.second?.let { builder.addQueryParameter("$param[]", it) }
            }
        }
    }

    internal class TypesFilter : MultiSelectFilter("Type", "type", YFlixFiltersData.TYPES)
    internal class QualitiesFilter : MultiSelectFilter("Quality", "quality", YFlixFiltersData.QUALITIES)
    internal class YearsFilter : MultiSelectFilter("Released", "year", YFlixFiltersData.YEARS)
    internal class GenresFilter : MultiSelectFilter("Genre", "genre", YFlixFiltersData.GENRES)
    internal class CountriesFilter : MultiSelectFilter("Country", "country", YFlixFiltersData.COUNTRIES)
    internal class SortByFilter : QueryPartFilter("Sort By", "sort", YFlixFiltersData.SORT_BY)

    val FILTER_LIST
        get() = FilterList(
            TypesFilter(),
            QualitiesFilter(),
            YearsFilter(),
            GenresFilter(),
            CountriesFilter(),
            SortByFilter(),
        )

    private object YFlixFiltersData {
        val TYPES = listOf(
            "Movie" to "movie",
            "TV-Shows" to "tv",
        )

        val QUALITIES = listOf(
            "HD" to "HD",
            "HDrip" to "HDrip",
            "SD" to "SD",
            "TS" to "TS",
            "CAM" to "CAM",
        )

        val YEARS = listOf(
            "2026" to "2026",
            "2025" to "2025",
            "2024" to "2024",
            "2023" to "2023",
            "2022" to "2022",
            "2021" to "2021",
            "2020" to "2020",
            "2019" to "2019",
            "2018" to "2018",
            "2017" to "2017",
            "2016" to "2016",
            "Older" to "older",
        )

        val SORT_BY = listOf(
            "Most relevant" to "most_relevance",
            "Updated date" to "updated_date",
            "Added date" to "added_date",
            "Release date" to "release_date",
            "Trending" to "trending",
            "Name A-Z" to "title_az",
            "Average score" to "score",
            "IMDb" to "imdb",
            "Most viewed" to "most_viewed",
            "Most followed" to "most_followed",
        )

        val GENRES = listOf(
            "Action" to "14",
            "Adult" to "15265",
            "Adventure" to "109",
            "Animation" to "404",
            "Biography" to "312",
            "Comedy" to "1",
            "Costume" to "50202",
            "Crime" to "126",
            "Documentary" to "92",
            "Drama" to "12",
            "Family" to "78",
            "Fantasy" to "53",
            "Film-Noir" to "1779",
            "Game-Show" to "966",
            "History" to "239",
            "Horror" to "2",
            "Kungfu" to "67893",
            "Music" to "99",
            "Musical" to "1809",
            "Mystery" to "154",
            "News" to "1515",
            "Reality" to "6774",
            "Reality-TV" to "726",
            "Romance" to "44",
            "Sci-Fi" to "162",
            "Short" to "405",
            "Sport" to "79",
            "Talk" to "92400",
            "Talk-Show" to "7024",
            "Thriller" to "13",
            "TV Movie" to "18067",
            "TV Show" to "11185",
            "War" to "436",
            "Western" to "1443",
        )

        val COUNTRIES = listOf(
            "Argentina" to "3388",
            "Australia" to "30",
            "Austria" to "1791",
            "Belgium" to "111",
            "Brazil" to "616",
            "Canada" to "64",
            "China" to "350",
            "Colombia" to "11332",
            "Czech Republic" to "5187",
            "Denmark" to "375",
            "Finland" to "3356",
            "France" to "16",
            "Germany" to "127",
            "Hong Kong" to "351",
            "Hungary" to "5042",
            "India" to "110",
            "Ireland" to "225",
            "Italy" to "163",
            "Japan" to "291",
            "Luxembourg" to "8087",
            "Mexico" to "1727",
            "Netherlands" to "867",
            "New Zealand" to "1616",
            "Nigeria" to "1618",
            "Norway" to "3357",
            "Philippines" to "4141",
            "Poland" to "5600",
            "Romania" to "5730",
            "Russia" to "6646",
            "South Africa" to "1541",
            "South Korea" to "360",
            "Spain" to "240",
            "Sweden" to "1728",
            "Switzerland" to "2521",
            "Taiwan" to "3564",
            "Thailand" to "9360",
            "Turkey" to "881",
            "United Kingdom" to "15",
            "United States" to "3",
        )
    }
}
